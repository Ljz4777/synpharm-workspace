# 阶段 B 端到端验证：需求基线的预检 / 冻结 / 幂等 / 版本演进
# 前置：9 个容器已起，网关 8080 可达，synpharm_core 已跑过 39_requirement_baseline.sql
#       靶点库有 EGFR（fixture-target.sql），MODALITY 字典有 ANTIBODY(id=2)
$ErrorActionPreference = 'Stop'
$GW = 'http://localhost:8080'
$pass = 0; $fail = 0

function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { $script:pass++; Write-Output ("PASS  {0}  {1}" -f $name, $detail) }
  else { $script:fail++; Write-Output ("FAIL  {0}  {1}" -f $name, $detail) }
}

function Invoke-Json([string]$method, [string]$path, $obj, [string]$token) {
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  $args = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $obj) {
    $args['ContentType'] = 'application/json; charset=utf-8'
    $args['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 8 -Compress))
  }
  try {
    $r = Invoke-WebRequest @args
    return @{ Status = [int]$r.StatusCode; Body = ([System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json) }
  } catch {
    $resp = $_.Exception.Response
    if ($null -eq $resp) { throw }
    $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $txt = $sr.ReadToEnd()
    $parsed = $null
    try { $parsed = $txt | ConvertFrom-Json } catch { }
    return @{ Status = [int]$resp.StatusCode; Body = $parsed; Raw = $txt }
  }
}

function Block-State($preview, [string]$code) {
  $b = @($preview.blocks) | Where-Object { $_.code -eq $code }
  if ($b) { return $b[0].state } else { return 'ABSENT' }
}

function Count-Data($resp) {
  if ($null -eq $resp -or $null -eq $resp.Body -or $null -eq $resp.Body.data) { return 0 }
  return @($resp.Body.data | Where-Object { $null -ne $_ }).Count
}

# PS 5.1 里 (pipeline).Count 在结果只有 1 个元素时返回空，必须用 @() 包住整个 pipeline
function Count-Gap($preview, [string]$code) {
  return @(@($preview.gaps) | Where-Object { $_.code -eq $code }).Count
}

function Short-Hash([string]$h) {
  if ([string]::IsNullOrEmpty($h) -or $h.Length -lt 8) { return '<empty>' }
  return $h.Substring(0, 8)
}

# ---------------------------------------------------------------- 0) 登录
$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"

# ---------------------------------------------------------------- 1) 靶点
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1
if (-not $target) {
  Write-Output "PRECONDITION FAIL  靶点库缺少 EGFR，请先执行 fixture-target.sql"
  exit 2
}
Check 'target.egfr' ($null -ne $target.id) "targetId=$($target.id) uniprot=$($target.uniprotId)"

# ---------------------------------------------------------------- 2) 立项（配额 + MOA + 内联约束）
$stamp = Get-Date -Format 'MMddHHmmss'
$createBody = @{
  name = "E2E 基线 $stamp"
  goalText = '改善亲和力并降低免疫原性'
  modalityId = 2
  moaCode = 'INHIBITOR'
  minCandidates = 20
  quotaGpuHours = 48
  quotaWetLab = 8
  targets = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true; sortNo = 1 })
  objectives = @(@{ dimension = 'AFFINITY'; operator = 'LTE'; threshold = 10; unit = 'nM'; weight = 0.4 })
  constraintRules = @(
    @{ ruleType = 'NUMERIC_RANGE'; severity = 'HARD'; params = @{ field = 'MolWt'; min = 200; max = 550 }; unit = 'Da'; weight = 10; humanReadable = '分子量 200~550 Da'; sortNo = 1 },
    @{ ruleType = 'NUMERIC_RANGE'; severity = 'SOFT'; params = @{ field = 'LogP'; min = -1; max = 5 }; weight = 6; humanReadable = 'LogP -1~5'; sortNo = 2 }
  )
}
$p = Invoke-Json 'POST' '/api/design/projects' $createBody $token
Check 'project.create' ($p.Status -eq 201) "HTTP $($p.Status) $($p.Raw)"
$projId = $p.Body.data.id
Check 'project.inlineConstraint' ($null -ne $projId) "projectId=$projId status=$($p.Body.data.status)"

# 配额与作用机制必须能从详情读回，否则基线块7 永远填不上
$detail = Invoke-Json 'GET' "/api/design/projects/$projId" $null $token
Check 'project.moa.echo' ($detail.Body.data.moaCode -eq 'INHIBITOR') "moaCode=$($detail.Body.data.moaCode)"
Check 'project.quota.echo' ($detail.Body.data.minCandidates -eq 20 -and $detail.Body.data.quotaGpuHours -eq 48 -and $detail.Body.data.quotaWetLab -eq 8) "min=$($detail.Body.data.minCandidates) gpu=$($detail.Body.data.quotaGpuHours) wet=$($detail.Body.data.quotaWetLab)"
Check 'project.baseline.empty' ($null -eq $detail.Body.data.currentBaselineId) "currentBaselineId=$($detail.Body.data.currentBaselineId)"

# ---------------------------------------------------------------- 3) 预检：缺指标 → 不可冻结
$pv1 = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:preview" $null $token
Check 'preview.status' ($pv1.Status -eq 200) "HTTP $($pv1.Status)"
Check 'preview.notReady' ($pv1.Body.data.ready -eq $false) "ready=$($pv1.Body.data.ready)"
Check 'preview.metrics.missing' ((Block-State $pv1.Body.data 'METRICS') -eq 'MISSING') "METRICS=$(Block-State $pv1.Body.data 'METRICS')"
Check 'preview.targets.ok' ((Block-State $pv1.Body.data 'TARGETS') -eq 'OK') "TARGETS=$(Block-State $pv1.Body.data 'TARGETS')"
Check 'preview.structure.ok' ((Block-State $pv1.Body.data 'STRUCTURE') -eq 'OK') "STRUCTURE=$(Block-State $pv1.Body.data 'STRUCTURE')"
Check 'preview.modality.ok' ((Block-State $pv1.Body.data 'MODALITY') -eq 'OK') "MODALITY=$(Block-State $pv1.Body.data 'MODALITY')"
Check 'preview.hardRules.ok' ((Block-State $pv1.Body.data 'HARD_RULES') -eq 'OK') "HARD_RULES=$(Block-State $pv1.Body.data 'HARD_RULES')"
Check 'preview.quota.ok' ((Block-State $pv1.Body.data 'QUOTA') -eq 'OK') "QUOTA=$(Block-State $pv1.Body.data 'QUOTA')"
Check 'preview.softRules.ok' ((Block-State $pv1.Body.data 'SOFT_RULES') -eq 'OK') "SOFT_RULES=$(Block-State $pv1.Body.data 'SOFT_RULES')"
Check 'preview.risks.empty' ((Block-State $pv1.Body.data 'RISKS') -eq 'OK') "RISKS=$(Block-State $pv1.Body.data 'RISKS')"
$gapCodes = (@($pv1.Body.data.gaps) | Where-Object { $_.severity -eq 'ERROR' } | ForEach-Object { $_.code }) -join ','
Check 'preview.gap.noMetric' ($gapCodes -match 'NO_METRIC') "errors=$gapCodes"

# 缺项时冻结必须拒绝，且不产生快照
$early = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{} $token
Check 'freeze.incomplete409' ($early.Status -eq 409 -and $early.Body.errorCode -eq 'BASELINE_INCOMPLETE') "HTTP $($early.Status) errorCode=$($early.Body.errorCode)"
Check 'freeze.incomplete.text' ($early.Body.message -match '不完整') "message=$($early.Body.message)"
$detail2 = Invoke-Json 'GET' "/api/design/projects/$projId" $null $token
Check 'freeze.incomplete.noSnapshot' ($null -eq $detail2.Body.data.currentBaselineId) "currentBaselineId=$($detail2.Body.data.currentBaselineId)"

# ---------------------------------------------------------------- 4) 补齐指标与风险
$mi = Invoke-Json 'POST' "/api/design/projects/$projId/metrics:init-from-modality" $null $token
$initCount = Count-Data $mi
Check 'metrics.initFromModality' ($mi.Status -eq 200 -and $initCount -ge 1) "HTTP $($mi.Status) 补入 $initCount 个指标 $($mi.Raw)"

$rk = Invoke-Json 'POST' "/api/design/projects/$projId/risks" @{
  riskCode = 'PTM_HOTSPOT'; dimension = 'STABILITY'
  description = 'CDR 区存在 N-X-S 糖基化位点风险'; severity = 'MEDIUM'; mustVerify = $true; source = 'MANUAL'
} $token
Check 'risk.create' ($rk.Status -eq 200) "HTTP $($rk.Status) $($rk.Raw)"

$pv2 = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:preview" $null $token
Check 'preview.ready' ($pv2.Body.data.ready -eq $true) "ready=$($pv2.Body.data.ready) gaps=$((@($pv2.Body.data.gaps)).Count)"
Check 'preview.metrics.ok' ((Block-State $pv2.Body.data 'METRICS') -eq 'OK') "METRICS=$(Block-State $pv2.Body.data 'METRICS')"
Check 'preview.risks.ok' ((Block-State $pv2.Body.data 'RISKS') -eq 'OK') "RISKS=$(Block-State $pv2.Body.data 'RISKS')"
$hash1 = $pv2.Body.data.contentHash
Check 'preview.hash' ($hash1 -match '^[0-9a-f]{64}$') "hash=$(Short-Hash $hash1)"

# ---------------------------------------------------------------- 5) 冻结 v1
$f1 = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $hash1 } $token
Check 'freeze.v1' ($f1.Status -eq 200 -and $f1.Body.data.projectVersion -eq 1) "HTTP $($f1.Status) version=$($f1.Body.data.projectVersion)"
Check 'freeze.v1.active' ($f1.Body.data.status -eq 'ACTIVE') "status=$($f1.Body.data.status)"
Check 'freeze.v1.eightBlocks' (@($f1.Body.data.completeness.PSObject.Properties).Count -eq 8) "blocks=$((@($f1.Body.data.completeness.PSObject.Properties).Name) -join ',')"
Check 'freeze.v1.modality' ($f1.Body.data.modalityCode -eq 'ANTIBODY') "modalityCode=$($f1.Body.data.modalityCode)"
$snap1 = $f1.Body.data.id

# 项目侧回写
$detail3 = Invoke-Json 'GET' "/api/design/projects/$projId" $null $token
Check 'freeze.project.pointer' ($detail3.Body.data.currentBaselineId -eq $snap1) "currentBaselineId=$($detail3.Body.data.currentBaselineId)"
Check 'freeze.project.version' ($detail3.Body.data.projectVersion -eq 1) "projectVersion=$($detail3.Body.data.projectVersion)"

# ---------------------------------------------------------------- 6) 幂等
$f2 = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $hash1 } $token
Check 'freeze.idempotent.id' ($f2.Body.data.id -eq $snap1) "snapshotId=$($f2.Body.data.id)"
Check 'freeze.idempotent.version' ($f2.Body.data.projectVersion -eq 1) "version=$($f2.Body.data.projectVersion)"
$h1 = Invoke-Json 'GET' "/api/design/projects/$projId/baseline/history" $null $token
Check 'history.len1' (@($h1.Body.data).Count -eq 1) "count=$((@($h1.Body.data)).Count)"

# ---------------------------------------------------------------- 7) 内容变更 → 新版
$ms = Invoke-Json 'GET' "/api/design/projects/$projId/metrics" $null $token
$metric0 = @($ms.Body.data)[0]
$mu = Invoke-Json 'PUT' "/api/design/projects/$projId/metrics/$($metric0.id)" @{ weight = 0.33 } $token
Check 'metric.update' ($mu.Status -eq 200 -and $mu.Body.data.weight -eq 0.33) "HTTP $($mu.Status) weight=$($mu.Body.data.weight)"

$pv3 = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:preview" $null $token
$hash2 = $pv3.Body.data.contentHash
Check 'preview.hashChanged' ($hash2 -ne $hash1) "old=$(Short-Hash $hash1) new=$(Short-Hash $hash2)"

# 拿旧指纹提交必须被挡住
$stale = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $hash1 } $token
Check 'freeze.stale409' ($stale.Status -eq 409 -and $stale.Body.errorCode -eq 'BASELINE_STALE') "HTTP $($stale.Status) errorCode=$($stale.Body.errorCode)"

$f3 = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $hash2 } $token
Check 'freeze.v2' ($f3.Body.data.projectVersion -eq 2 -and $f3.Body.data.status -eq 'ACTIVE') "version=$($f3.Body.data.projectVersion) status=$($f3.Body.data.status)"
$snap2 = $f3.Body.data.id

$h2 = Invoke-Json 'GET' "/api/design/projects/$projId/baseline/history" $null $token
$hist = @($h2.Body.data)
Check 'history.len2' ($hist.Count -eq 2) "count=$($hist.Count)"
Check 'history.sort' ($hist[0].projectVersion -eq 2 -and $hist[1].projectVersion -eq 1) "versions=$($hist[0].projectVersion),$($hist[1].projectVersion)"
Check 'history.superseded' ($hist[1].status -eq 'SUPERSEDED' -and $hist[1].supersededBy -eq $snap2) "v1.status=$($hist[1].status) supersededBy=$($hist[1].supersededBy)"
Check 'history.contentPreserved' ($hist[1].contentHash -eq $hash1) "v1.hash 未变"

$cur = Invoke-Json 'GET' "/api/design/projects/$projId/baseline" $null $token
Check 'baseline.current' ($cur.Body.data.id -eq $snap2 -and $cur.Body.data.projectVersion -eq 2) "id=$($cur.Body.data.id) version=$($cur.Body.data.projectVersion)"

# ---------------------------------------------------------------- 8) 守卫
$anon = Invoke-Json 'GET' "/api/design/projects/$projId/baseline" $null $null
Check 'baseline.anon401' ($anon.Status -eq 401) "HTTP $($anon.Status) errorCode=$($anon.Body.errorCode)"

$nf = Invoke-Json 'GET' '/api/design/projects/99999999/baseline' $null $token
Check 'baseline.missing404' ($nf.Status -eq 404) "HTTP $($nf.Status) errorCode=$($nf.Body.errorCode)"

$badMoa = Invoke-Json 'POST' '/api/design/projects' @{
  name = "E2E 非法 MOA $stamp"; modalityId = 2; moaCode = 'NOT_A_MOA'
  targets = @(@{ targetId = $target.id; isPrimary = $true })
} $token
Check 'project.badMoa400' ($badMoa.Status -eq 400) "HTTP $($badMoa.Status) errorCode=$($badMoa.Body.errorCode)"

# 没配额的项目：min_candidates 未设 → QUOTA 块 MISSING，不许冻结
$p2 = Invoke-Json 'POST' '/api/design/projects' @{
  name = "E2E 无配额 $stamp"; modalityId = 2; moaCode = 'AGONIST'
  targets = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
} $token
$proj2 = $p2.Body.data.id
Check 'project2.create' ($p2.Status -eq 201 -and $null -ne $proj2) "HTTP $($p2.Status) projectId=$proj2"
$pv4 = Invoke-Json 'POST' "/api/design/projects/$proj2/baseline:preview" $null $token
Check 'preview2.quota.missing' ((Block-State $pv4.Body.data 'QUOTA') -eq 'MISSING') "QUOTA=$(Block-State $pv4.Body.data 'QUOTA')"
Check 'preview2.quota.gap' ((Count-Gap $pv4.Body.data 'NO_MIN_CANDIDATES') -eq 1) "NO_MIN_CANDIDATES=$(Count-Gap $pv4.Body.data 'NO_MIN_CANDIDATES')"
$f4 = Invoke-Json 'POST' "/api/design/projects/$proj2/baseline:freeze" @{} $token
Check 'freeze2.incomplete409' ($f4.Status -eq 409 -and $f4.Body.errorCode -eq 'BASELINE_INCOMPLETE') "HTTP $($f4.Status) errorCode=$($f4.Body.errorCode)"

# 补 min_candidates 与 GPU 后 QUOTA 转为 WARN（wet 仍未设 → 上限缺失的告警），不是 OK
$patch = Invoke-Json 'PATCH' "/api/design/projects/$proj2" @{ minCandidates = 5; quotaGpuHours = 8 } $token
Check 'project.patch.quota' ($patch.Status -eq 200 -and $patch.Body.data.minCandidates -eq 5) "HTTP $($patch.Status) min=$($patch.Body.data.minCandidates)"
$pv5 = Invoke-Json 'POST' "/api/design/projects/$proj2/baseline:preview" $null $token
Check 'preview2.quota.warn' ((Block-State $pv5.Body.data 'QUOTA') -eq 'WARN') "QUOTA=$(Block-State $pv5.Body.data 'QUOTA')（wet 未设应告警）"
Check 'preview2.quota.wetGap' ((Count-Gap $pv5.Body.data 'QUOTA_WET_LAB_UNLIMITED') -eq 1) "QUOTA_WET_LAB_UNLIMITED=$(Count-Gap $pv5.Body.data 'QUOTA_WET_LAB_UNLIMITED')"

$mi2 = Invoke-Json 'POST' "/api/design/projects/$proj2/metrics:init-from-modality" $null $token
$init2 = Count-Data $mi2
Check 'project2.metrics.init' ($mi2.Status -eq 200 -and $init2 -ge 1) "HTTP $($mi2.Status) 补入 $init2 个指标"
$pv6 = Invoke-Json 'POST' "/api/design/projects/$proj2/baseline:preview" $null $token
Check 'preview2.afterPatch' ((Block-State $pv6.Body.data 'QUOTA') -eq 'WARN' -and (Block-State $pv6.Body.data 'METRICS') -eq 'OK') "QUOTA=$(Block-State $pv6.Body.data 'QUOTA') METRICS=$(Block-State $pv6.Body.data 'METRICS')"
# 该项目没绑约束集：缺硬约束是 ERROR，所以即便配额与指标补齐也还不能冻结
Check 'preview2.hardRules.missing' ((Block-State $pv6.Body.data 'HARD_RULES') -eq 'MISSING') "HARD_RULES=$(Block-State $pv6.Body.data 'HARD_RULES')"
Check 'preview2.stillNotReady' ($pv6.Body.data.ready -eq $false) "ready=$($pv6.Body.data.ready)（缺硬约束）"

Write-Output ''
Write-Output "RESULT pass=$pass fail=$fail projectId=$projId snapshots=$snap1,$snap2"
exit $(if ($fail -eq 0) { 0 } else { 1 })
