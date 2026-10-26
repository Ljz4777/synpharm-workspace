# 阶段 D 端到端验证：最小评估（D2）+ E0/E1 诊断（D3）+ E2 匹配（D4）+ E3 影响面（D5）+ 版本树（D8）
# 前置：9 个容器已起、synpharm_core 已跑 39 + 40 + 41 + 42 脚本、fastapi /v1/evaluate 可用
#
# 覆盖两条真实路径：
#   ① 小分子：真 RDKit 指标 → 真硬约束判定 → 真冻结快照 → 真缺口诊断
#   ② 准入守卫：无 freeze 拒绝诊断 / 无缺口报告拒绝 E3 / 分支放弃 / 回退新建版本
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
  $req = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $obj) {
    $req['ContentType'] = 'application/json; charset=utf-8'
    $req['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 8 -Compress))
  }
  try {
    $r = Invoke-WebRequest @req
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

# ---------------------------------------------------------------- 等待网关就绪
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
  try {
    $probe = Invoke-WebRequest -Uri "$GW/actuator/health" -UseBasicParsing -TimeoutSec 3
    if ($probe.StatusCode -eq 200) { $ready = $true; break }
  } catch { }
  Start-Sleep -Seconds 4
}
if (-not $ready) { Write-Output 'GATEWAY NOT READY'; exit 2 }

# ---------------------------------------------------------------- 登录与靶点
$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"

$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1
Check 'target.egfr' ($null -ne $target.id) "targetId=$($target.id)"

$stamp = Get-Date -Format 'HHmmss'

# 供 New-FrozenProject 使用（PowerShell 函数内读不到调用方局部变量）
$script:token = $token
$script:targetId = $target.id

# ---------------------------------------------------------------- 工具函数
# 用 $global:SETUP 中转而不是 return：函数体内任何一处意外输出都会让返回值变成数组，
# 而 $sm.ProjectId 在数组上取不到，表现为"创建成功但 id 为空"这种极难查的现象
function New-FrozenProject([string]$name, [int]$modalityId, [int]$minCandidates) {
  $reqBody = @{
    name = $name; modalityId = $modalityId; moaCode = 'INHIBITOR'
    minCandidates = $minCandidates; quotaGpuHours = 48; quotaWetLab = 8
    targets = @(@{ targetId = $script:targetId; role = 'WILD_TYPE'; isPrimary = $true })
    constraintRules = @(@{
      ruleType = 'NUMERIC_RANGE'; severity = 'HARD'
      params = @{ field = 'MolWt'; min = 150; max = 600 }
      weight = 10; humanReadable = 'MW 150~600'
    })
  }

  $created = Invoke-Json 'POST' '/api/design/projects' $reqBody $script:token
  $newId = $created.Body.data.id
  if (-not $newId) {
    Write-Output ("SETUP FAIL 项目创建失败：HTTP {0} {1}" -f $created.Status, $created.Raw)
    $global:SETUP = @{ ProjectId = $null }
    return
  }
  $null = Invoke-Json 'POST' "/api/design/projects/$newId/metrics:init-from-modality" $null $script:token
  $pv = Invoke-Json 'POST' "/api/design/projects/$newId/baseline:preview" $null $script:token
  $fz = Invoke-Json 'POST' "/api/design/projects/$newId/baseline:freeze" @{ expectedContentHash = $pv.Body.data.contentHash } $script:token
  $rd = Invoke-Json 'POST' "/api/design/projects/$newId/rounds" @{ strategyNote = 'edit' } $script:token

  $global:SETUP = @{ ProjectId = $newId; Freeze = $fz; Round = $rd; Preview = $pv }
}

# ================================================================ ① 小分子：评估 → 冻结 → 诊断
Write-Output ''
Write-Output '=== ① 最小评估与缺口诊断（小分子，真 RDKit）==='
New-FrozenProject "评估-小分子 $stamp" 1 3; $sm = $global:SETUP
Check 'setup.project' ($null -ne $sm.ProjectId) "projectId=$($sm.ProjectId)"
Check 'setup.freeze' ($sm.Freeze.Status -eq 200 -and $sm.Freeze.Body.data.status -eq 'ACTIVE') "baselineId=$($sm.Freeze.Body.data.id)"

# 布洛芬：MW 206（在 150~600 内，过硬约束）、PAINS/BRENK 干净。
# 阿司匹林会命中 BRENK 的 phenol_ester 被筛掉，用它测评估会得到「没有候选」的假失败
$gen = Invoke-Json 'POST' "/api/design/projects/$($sm.ProjectId)/generations" @{
  seeds = @(@{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' }); seed = 11
} $token
Check 'mol.generated' ($gen.Status -eq 202 -and $gen.Body.data.finalCount -ge 1) "runId=$($gen.Body.data.id) final=$($gen.Body.data.finalCount)"

$candList = Invoke-Json 'GET' "/api/design/projects/$($sm.ProjectId)" $null $token
$candidates = Invoke-Json 'GET' "/api/design/generations/$($gen.Body.data.id)/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
$candId = if (@($candidates.Body.data).Count -gt 0) { @($candidates.Body.data)[0].candidateId } else { $null }
Check 'mol.candidateId' ($null -ne $candId -and $candId -gt 0) "candidateId=$candId"

# ---- D2：评估并冻结 ----
$fz1 = Invoke-Json 'POST' "/api/design/candidates/$candId/evaluation:freeze" @{} $token
Check 'eval.freeze' ($fz1.Status -eq 200) "HTTP $($fz1.Status) $($fz1.Raw)"
$freeze = $fz1.Body.data
Check 'eval.freeze.no' ($freeze.freezeNo -match '^EV[0-9A-F]+') "freezeNo=$($freeze.freezeNo) version=$($freeze.versionNo)"
Check 'eval.hash' ($freeze.contentHash -match '^[0-9a-f]{64}$') "hash=$($freeze.contentHash)"

# 小分子 10 个指标里只有 4 个能算 → 覆盖率必然 < 1，且缺失项要如实列出
Check 'eval.coverage.partial' ($freeze.weightCoverage -lt 1) "coverage=$($freeze.weightCoverage) (小分子只有部分指标可算)"
Check 'eval.missing.listed' (@($freeze.missingMetrics).Count -ge 1) "missing=$(@($freeze.missingMetrics).Count) 项：$((@($freeze.missingMetrics) -join ','))"
Check 'eval.scores.real' ($null -ne $freeze.scores.QED.value) "QED=$($freeze.scores.QED.value) PAINS=$($freeze.scores.PAINS_HITS.value)"
Check 'eval.hardPassed' ($freeze.hardPassed -eq $true) "hardPassed=$($freeze.hardPassed) violations=$(@($freeze.violations).Count)"
Check 'eval.totalScore' ($null -ne $freeze.totalScore) "totalScore=$($freeze.totalScore)"

# 幂等：同候选同基线再冻结 → 返回同一条
$fz2 = Invoke-Json 'POST' "/api/design/candidates/$candId/evaluation:freeze" @{} $token
Check 'eval.idempotent' ($fz2.Body.data.id -eq $freeze.id) "id=$($fz2.Body.data.id) vs $($freeze.id)"

# 查询接口
$latest = Invoke-Json 'GET' "/api/design/candidates/$candId/evaluation" $null $token
Check 'eval.latest' ($latest.Status -eq 200 -and $latest.Body.data.id -eq $freeze.id) "HTTP $($latest.Status)"
$hist = Invoke-Json 'GET' "/api/design/candidates/$candId/evaluation/history" $null $token
Check 'eval.history' ($hist.Status -eq 200 -and @($hist.Body.data).Count -ge 1) "history=$(@($hist.Body.data).Count)"

# ---- D3：E1 缺口诊断 ----
$dg = Invoke-Json 'POST' "/api/design/candidates/$candId/gaps:diagnose" @{} $token
Check 'gap.diagnose' ($dg.Status -eq 200) "HTTP $($dg.Status) $($dg.Raw)"
$gap = $dg.Body.data
Check 'gap.reportNo' ($gap.reportNo -match '^GAP[0-9A-F]+') "reportNo=$($gap.reportNo) freezeId=$($gap.evalFreezeId)"
Check 'gap.noGaps.protected' ($null -ne $gap.noGaps) "noGaps=[$($gap.noGaps -join ',')]"
# 未算出的指标（SA_SCORE/DTI/...）必然产生 NOT_EVALUATED 缺口
$allGaps = @($gap.gaps) + @($gap.unfixable)
Check 'gap.notEvaluated' ((@($allGaps | Where-Object { $_.gapType -eq 'NOT_EVALUATED' }).Count) -ge 1) "NOT_EVALUATED=$(@($allGaps | Where-Object { $_.gapType -eq 'NOT_EVALUATED' }).Count)"
# 归因恒为 null 且给出说明（不许假装看懂了结构）
Check 'gap.attribution.null' ((@($allGaps | Where-Object { $null -eq $_.attribution }).Count) -eq $allGaps.Count) "归因全为 null=$($allGaps.Count)"
Check 'gap.attributionNote' ($gap.attributionNote -match '归因模型') "note=$($gap.attributionNote)"
Check 'gap.unfixableListed' ($null -ne $gap.unfixable) "unfixable=$(@($gap.unfixable).Count) gaps=$(@($gap.gaps).Count)"
# E2 匹配结果随诊断一起返回
Check 'gap.actions' ($null -ne $gap.actions) "actions=$(@($gap.actions).Count)"
$actionsText = ''
foreach ($a in @($gap.actions)) { $actionsText += "$($a.actionCode)/$($a.gapDimension)/est=$($a.rateIsEstimate);" }
Check 'gap.actions.estimateFlag' ($actionsText.Length -gt 0) "actions=$actionsText"

# 最新诊断可查
$latestGap = Invoke-Json 'GET' "/api/design/candidates/$candId/gaps" $null $token
Check 'gap.latest' ($latestGap.Status -eq 200 -and $latestGap.Body.data.reportNo -eq $gap.reportNo) "HTTP $($latestGap.Status)"

# ---- D5：E3 影响面 ----
# SCAFFOLD_HOP 的副作用是 {"AFFINITY":"MUST_DROP"}；小分子 AFFINITY 未评估（未达标）
# → 应为 WARN 而非 BLOCK
$imp = Invoke-Json 'POST' "/api/design/candidates/$candId/actions:preview" @{ actionCode = 'SCAFFOLD_HOP'; targetRegion = 'core' } $token
Check 'impact.preview' ($imp.Status -eq 200) "HTTP $($imp.Status) $($imp.Raw)"
if ($imp.Status -eq 200) {
  $impact = $imp.Body.data
  Check 'impact.guard' ($impact.guardResult -in @('PASS','WARN','BLOCK')) "guard=$($impact.guardResult) executable=$($impact.executable)"
  Check 'impact.predictedDelta.null' ((@($impact.protectedOnes | Where-Object { $null -eq $_.predictedDelta }).Count) -eq @($impact.protectedOnes).Count) "delta 全为 null（量化模型未接入）"
  Check 'impact.executable.consistent' (($impact.guardResult -ne 'BLOCK') -eq $impact.executable) "guard=$($impact.guardResult) executable=$($impact.executable)"
}

# 无效动作 → 404
$badAction = Invoke-Json 'POST' "/api/design/candidates/$candId/actions:preview" @{ actionCode = 'NOT_A_REAL_ACTION' } $token
Check 'impact.unknownAction' ($badAction.Status -eq 404) "HTTP $($badAction.Status) errorCode=$($badAction.Body.errorCode)"

# ================================================================ ② 版本树与回退
Write-Output ''
Write-Output '=== ② 版本树 / 放弃 / 回退 ==='
$tree = Invoke-Json 'GET' "/api/design/candidates/$candId/tree" $null $token
Check 'tree.root' ($tree.Status -eq 200 -and $tree.Body.data.rootId -eq $candId) "rootId=$($tree.Body.data.rootId) nodes=$(@($tree.Body.data.nodes).Count)"
Check 'tree.focus' ($tree.Body.data.focusId -eq $candId) "focusId=$($tree.Body.data.focusId)"
Check 'tree.hasFreeze' (@($tree.Body.data.nodes)[0].hasFreeze -eq $true) "hasFreeze=$(@($tree.Body.data.nodes)[0].hasFreeze)"

# 根版本没有父可回退 → 409
$revertRoot = Invoke-Json 'POST' "/api/design/candidates/$candId`:revert" @{} $token
Check 'tree.revertRootConflict' ($revertRoot.Status -eq 409) "HTTP $($revertRoot.Status) errorCode=$($revertRoot.Body.errorCode)"

# ================================================================ ③ 准入守卫
Write-Output ''
Write-Output '=== ③ 准入与守卫 ==='
# 新项目里刚生成、未评估的候选：诊断应 404（没有冻结快照）
New-FrozenProject "评估-守卫 $stamp" 1 2; $gd = $global:SETUP
$gen2 = Invoke-Json 'POST' "/api/design/projects/$($gd.ProjectId)/generations" @{
  seeds = @(@{ ref = 'CAF'; smiles = 'CN1C=NC2=C1C(=O)N(C)C(=O)N2C' }); seed = 22
} $token
$cands2 = Invoke-Json 'GET' "/api/design/generations/$($gen2.Body.data.id)/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
$candId2 = if (@($cands2.Body.data).Count -gt 0) { @($cands2.Body.data)[0].candidateId } else { $null }
Check 'guard.candidate2' ($null -ne $candId2) "candidateId=$candId2"

$noFreeze = Invoke-Json 'POST' "/api/design/candidates/$candId2/gaps:diagnose" @{} $token
Check 'guard.noFreeze' ($noFreeze.Status -eq 404) "HTTP $($noFreeze.Status) errorCode=$($noFreeze.Body.errorCode)"
Check 'guard.noFreeze.hint' ($noFreeze.Body.message -match '冻结快照|评估') "msg=$($noFreeze.Body.message)"

$noLatest = Invoke-Json 'GET' "/api/design/candidates/$candId2/gaps" $null $token
Check 'guard.noGaps' ($noLatest.Status -eq 404) "HTTP $($noLatest.Status) errorCode=$($noLatest.Body.errorCode)"

$noReport = Invoke-Json 'POST' "/api/design/candidates/$candId2/actions:preview" @{ actionCode = 'SCAFFOLD_HOP' } $token
Check 'guard.impactNoReport' ($noReport.Status -eq 404) "HTTP $($noReport.Status) errorCode=$($noReport.Body.errorCode)"

# 评估后守卫应放行（咖啡因能算出指标）
$fz3 = Invoke-Json 'POST' "/api/design/candidates/$candId2/evaluation:freeze" @{} $token
Check 'guard.freeze2' ($fz3.Status -eq 200) "freezeNo=$($fz3.Body.data.freezeNo) coverage=$($fz3.Body.data.weightCoverage)"

# 放弃分支后不能再诊断
$ab = Invoke-Json 'POST' "/api/design/candidates/$candId2`:abandon" @{} $token
Check 'guard.abandon' ($ab.Status -eq 200) "HTTP $($ab.Status) $($ab.Raw)"
$afterAbandon = Invoke-Json 'POST' "/api/design/candidates/$candId2/gaps:diagnose" @{} $token
Check 'guard.abandonedBlocked' ($afterAbandon.Status -eq 409) "HTTP $($afterAbandon.Status) errorCode=$($afterAbandon.Body.errorCode)"
Check 'guard.abandonedHint' ($afterAbandon.Body.message -match '放弃') "msg=$($afterAbandon.Body.message)"

# 匿名与越权
$anon = Invoke-Json 'GET' "/api/design/candidates/$candId/evaluation" $null $null
Check 'guard.anon' ($anon.Status -eq 401) "HTTP $($anon.Status)"

$other = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$foreign = Invoke-Json 'GET' "/api/design/candidates/$candId/evaluation" $null $other.Body.data.accessToken
Check 'guard.foreign' ($foreign.Status -eq 404) "HTTP $($foreign.Status) errorCode=$($foreign.Body.errorCode)"

Write-Output ''
Write-Output "RESULT pass=$pass fail=$fail"
Write-Output "molProject=$($sm.ProjectId) candidate=$candId freezeId=$($freeze.id) gapReport=$($gap.reportNo)"
exit $(if ($fail -eq 0) { 0 } else { 1 })
