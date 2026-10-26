# 决策页（E10 前端）的数据通路校验：按 Decision.vue 的调用顺序与参数，逐个断言页面真正要读的字段。
# 与 e2e-decision.ps1 的分工：那边验「算得对不对」，这边验「读回来的东西能不能撑起界面」——
# capacity（名额）在读取路径曾缺失、分子没有 molecule_name 只能靠 SMILES 分辨，都是这里先发现的。
$ErrorActionPreference = 'Stop'
$BASE = 'http://localhost:8080'
$pass = 0; $fail = 0

function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { $script:pass++; Write-Output ("PASS  {0}  {1}" -f $name, $detail) }
  else { $script:fail++; Write-Output ("FAIL  {0}  {1}" -f $name, $detail) }
}

function Invoke-Json([string]$method, [string]$path, $obj, [string]$token) {
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  $req = @{ Uri = "$BASE$path"; Method = $method; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $obj) {
    $req['ContentType'] = 'application/json; charset=utf-8'
    $req['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 8 -Compress))
  }
  try {
    $r = Invoke-WebRequest @req
    $txt = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    return @{ Status = [int]$r.StatusCode; Body = ($txt | ConvertFrom-Json) }
  } catch {
    $resp = $_.Exception.Response
    if ($null -eq $resp) { throw }
    $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $txt = $sr.ReadToEnd()
    $parsed = $null
    try { $parsed = $txt | ConvertFrom-Json } catch { }
    return @{ Status = [int]$resp.StatusCode; Body = $parsed }
  }
}

$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"

# ---- 造一份完整夹具（每个 guest 都是新用户，必须自己建项目）
$mod = Invoke-Json 'GET' '/api/design/library?category=MODALITY&page=1&pageSize=100' $null $token
$small = @($mod.Body.data.list) | Where-Object { $_.code -like '*SMALL*' } | Select-Object -First 1
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1

$body = @{
  name            = "UIDecision-$(Get-Date -Format 'HHmmss')"
  modalityId      = $small.id
  moaCode         = 'INHIBITOR'
  minCandidates   = 3
  quotaGpuHours   = 48
  quotaWetLab     = 8
  targets         = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
  constraintRules = @(@{
    ruleType      = 'NUMERIC_RANGE'; severity = 'HARD'
    params        = @{ field = 'MolWt'; min = 150; max = 600 }
    weight        = 10; humanReadable = 'MW 150-600'
  })
}
$created = Invoke-Json 'POST' '/api/design/projects' $body $token
$projId = $created.Body.data.id
$null = Invoke-Json 'POST' "/api/design/projects/$projId/metrics:init-from-modality" @{} $token
$pv = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:preview" @{} $token
$null = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $pv.Body.data.contentHash } $token
$null = Invoke-Json 'POST' "/api/design/projects/$projId/rounds" @{ strategyNote = 'ui' } $token
$gen = Invoke-Json 'POST' "/api/design/projects/$projId/generations" @{
  seeds = @(
    @{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' },
    @{ ref = 'ASP'; smiles = 'CC(=O)Oc1ccccc1C(=O)O' },
    @{ ref = 'CAF'; smiles = 'Cn1cnc2c1c(=O)n(C)c(=O)n2C' }
  )
  seed = 42
  idempotencyKey = "ui-$projId"
} $token
$runId = $gen.Body.data.id
$kept = Invoke-Json 'GET' "/api/design/generations/$runId/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
$cands = @(@($kept.Body.data) | Where-Object { $_.candidateId })
foreach ($c in $cands) { $null = Invoke-Json 'POST' "/api/design/candidates/$($c.candidateId)/evaluation:freeze" @{} $token }
Check 'fixture' ($projId -gt 0 -and $cands.Count -ge 2) "project=$projId kept=$($cands.Count)"

$p = Invoke-Json 'GET' "/api/design/projects/$projId" $null $token
Check 'ui.getProject' ($p.Status -eq 200 -and $p.Body.data.name) "name=$($p.Body.data.name) capMode=$($p.Body.data.decisionCapacityMode) capValue=$($p.Body.data.decisionCapacityValue) wetQuota=$($p.Body.data.quotaWetLab)"

$m = Invoke-Json 'GET' "/api/design/projects/$projId/metrics" $null $token
$metrics = @($m.Body.data)
Check 'ui.listMetrics' ($m.Status -eq 200 -and $metrics.Count -gt 0) "metrics=$($metrics.Count)"

$hist = Invoke-Json 'GET' "/api/design/projects/$projId/decisions" $null $token
$histCount = @($hist.Body.data).Count
Check 'ui.listDecisions.empty' ($hist.Status -eq 200 -and $histCount -eq 0) "history=$histCount"

if ($histCount -eq 0) {
  $made = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{} $token
  Check 'ui.createDecision' ($made.Status -eq 200) "decisionNo=$($made.Body.data.decisionNo) code=$($made.Body.errorCode)"
  $hist = Invoke-Json 'GET' "/api/design/projects/$projId/decisions" $null $token
  $histCount = @($hist.Body.data).Count
}

$decId = @($hist.Body.data)[0].id
$d = Invoke-Json 'GET' "/api/design/decisions/$decId" $null $token
$dd = $d.Body.data
Check 'ui.getDecision' ($d.Status -eq 200 -and $dd.decisionNo) "no=$($dd.decisionNo) status=$($dd.status)"

# ---- 椤甸潰璇荤殑姣忎釜瀛楁閮借鍦?
Check 'ui.capacity' ($null -ne $dd.capacity) "capacity=$($dd.capacity) selected=$($dd.selectedCount) backup=$($dd.backupCount) rejected=$($dd.rejectedCount) deferred=$($dd.deferredCount)"
Check 'ui.warnings' ($null -ne $dd.warnings) "warnings=$(@($dd.warnings) -join ',')"
Check 'ui.ruleVersion' ([bool]$dd.ruleVersion) "ruleVersion=$($dd.ruleVersion)"
Check 'ui.inputSnapshotHash' ([bool]$dd.inputSnapshotHash) "hash=$($dd.inputSnapshotHash)"
Check 'ui.weightsSnapshot' (@($dd.weightsSnapshot.PSObject.Properties).Count -gt 0) "weightKeys=$(@($dd.weightsSnapshot.PSObject.Properties).Count)"
Check 'ui.candidates' (@($dd.candidates).Count -gt 0) "candidates=$(@($dd.candidates).Count)"

# weightsSnapshot 鐨勬瘡涓?key 閮借鑳藉湪椤圭洰鎸囨爣閲屾煡鍥炰腑鏂囧悕锛屽惁鍒欐潈閲嶈〃鍙兘鏄剧ず鏈哄櫒鐮?
$codes = @($dd.weightsSnapshot.PSObject.Properties | ForEach-Object { $_.Name })
$codes = @($codes | ForEach-Object { "$_".Trim() })
$metricNames = @($metrics | ForEach-Object { $_.metricCode })
$unmapped = @($codes | Where-Object { $metricNames -notcontains $_ })
Check 'ui.weightNamesResolve' ($unmapped.Count -eq 0) "unmapped=[$($unmapped -join ',')]"

# 鍊欓€夊瓧娈?
$c0 = @($dd.candidates)[0]
Check 'ui.candidateFields' (($null -ne $c0.candidateId) -and ($null -ne $c0.bucket) -and ($null -ne $c0.outcome) -and ($null -ne $c0.coversMetrics)) `
  "cand=$($c0.candidateId) bucket=$($c0.bucket) outcome=$($c0.outcome) rank=$($c0.rankNo) score=$($c0.weightedScore) covers=$($c0.coversMetrics) missing=$(@($c0.missingMetrics).Count)"
Check 'ui.moleculeNameOrSmiles' ([bool]$c0.moleculeName -or [bool]$c0.smiles) "moleculeName=$($c0.moleculeName) smiles=$($c0.smiles)"
# 生成阶段的分子通常没有 molecule_name：界面靠 smiles + MW 分辨，两者必须带出来
Check 'ui.moleculeIdentity' ([bool]$c0.smiles) `
  "smiles=$(if ($c0.smiles) { $c0.smiles } else { '(空)' }) mw=$($c0.mw)"

# 回放校验必须在「输入没变」的前提下比 —— 后面插入 must-verify 风险会改证据包 hash，属正常差异
$replay = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = 'ui replay check' } $token
$replaySame = ($replay.Body.data.inputSnapshotHash -eq $dd.inputSnapshotHash)
Check 'ui.replaySame' $replaySame "this=$($dd.inputSnapshotHash.Substring(0,8)) again=$($replay.Body.data.inputSnapshotHash.Substring(0,8))"

$withVeto = @($dd.candidates) | Where-Object { @($_.vetoes).Count -gt 0 } | Select-Object -First 1
$withReason = @($dd.candidates) | Where-Object { $_.rejectReason } | Select-Object -First 1

# 干净一轮里通常只有「名额不够」这一种原因；再开一轮带「必须验证」风险，把 BLOCK 否决打出来
$risk = Invoke-Json 'POST' "/api/design/projects/$projId/risks" @{
  riskCode = 'PTM_HOTSPOT'; dimension = 'STABILITY'
  description = 'UI check must-verify'; severity = 'MEDIUM'; mustVerify = $true
} $token
$dv = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = 'ui veto check' } $token
$withVeto = @($dv.Body.data.candidates) | Where-Object { @($_.vetoes).Count -gt 0 } | Select-Object -First 1
$withReason = @($dv.Body.data.candidates) | Where-Object { $_.rejectReason } | Select-Object -First 1
Check 'ui.vetoesOrReason' (($null -ne $withVeto) -or ($null -ne $withReason)) `
  "vetoHits=$(if ($withVeto) { @($withVeto.vetoes).Count } else { 0 }) firstReason=$(if ($withReason) { $withReason.rejectReason } else { '-' })"
if ($withVeto) {
  $h = @($withVeto.vetoes)[0]
  Check 'ui.vetoFields' ([bool]$h.ruleCode -and [bool]$h.severity -and [bool]$h.message) `
    "rule=$($h.ruleCode) sev=$($h.severity) ref=$($h.evidenceRef)"
}

# ---- 鍚﹀喅瑙勫垯闈㈡澘
$r = Invoke-Json 'GET' "/api/design/projects/$projId/decision-veto-rules" $null $token
$rules = @($r.Body.data)
Check 'ui.listRules' ($r.Status -eq 200 -and $rules.Count -gt 0) "rules=$($rules.Count) global=$(@($rules | Where-Object { $_.global }).Count)"
$g0 = @($rules | Where-Object { $_.global })[0]
Check 'ui.ruleFields' (($null -ne $g0.humanReadable) -and ($null -ne $g0.enabled) -and ($null -ne $g0.severity)) `
  "humanReadable=$($g0.humanReadable) sev=$($g0.severity) enabled=$($g0.enabled)"

$newRule = @{ ruleCode = 'FTO_RISK_HIGH'; severity = 'WARN'; humanReadable = 'UI smoke: FTO score above 0.85 blocks'; thresholdNum = 0.85 }
$c1 = Invoke-Json 'POST' "/api/design/projects/$projId/decision-veto-rules" $newRule $token
Check 'ui.createRule' ($c1.Status -eq 200) "id=$($c1.Body.data.id) code=$($c1.Body.errorCode)"
$rid = $c1.Body.data.id
$upd = $newRule.Clone(); $upd['enabled'] = $false
$u1 = Invoke-Json 'PUT' "/api/design/decision-veto-rules/$rid" $upd $token
Check 'ui.toggleRule' ($u1.Status -eq 200 -and $u1.Body.data.enabled -eq $false) "enabled=$($u1.Body.data.enabled)"
$d1 = Invoke-Json 'DELETE' "/api/design/decision-veto-rules/$rid" $null $token
Check 'ui.deleteRule' ($d1.Status -eq 200) "status=$($d1.Status)"

# ---- 页面上的写操作
# 单独起一个干净项目：本项目前面已经加过 must-verify 风险，后来的候选全部被否，
# 用它测不到「确认」这类需要至少一个入选候选的操作。
function New-UiFixture([string]$name, [string]$tag) {
  $created3 = Invoke-Json 'POST' '/api/design/projects' @{
    name            = $name
    modalityId      = $small.id
    moaCode         = 'INHIBITOR'
    minCandidates   = 3
    quotaGpuHours   = 48
    quotaWetLab     = 8
    targets         = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
    constraintRules = @(@{
      ruleType = 'NUMERIC_RANGE'; severity = 'HARD'
      params = @{ field = 'MolWt'; min = 150; max = 600 }
      weight = 10; humanReadable = 'MW 150-600'
    })
  } $token
  $pid3 = $created3.Body.data.id
  $null = Invoke-Json 'POST' "/api/design/projects/$pid3/metrics:init-from-modality" @{} $token
  $pv3 = Invoke-Json 'POST' "/api/design/projects/$pid3/baseline:preview" @{} $token
  $null = Invoke-Json 'POST' "/api/design/projects/$pid3/baseline:freeze" @{ expectedContentHash = $pv3.Body.data.contentHash } $token
  $null = Invoke-Json 'POST' "/api/design/projects/$pid3/rounds" @{ strategyNote = 'ui-act' } $token
  $gen3 = Invoke-Json 'POST' "/api/design/projects/$pid3/generations" @{
    seeds = @(
      @{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' },
      @{ ref = 'ASP'; smiles = 'CC(=O)Oc1ccccc1C(=O)O' }
    )
    seed = 42
    idempotencyKey = "uiact-$tag-$pid3"
  } $token
  $kept3 = Invoke-Json 'GET' "/api/design/generations/$($gen3.Body.data.id)/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
  $cands3 = @(@($kept3.Body.data) | Where-Object { $_.candidateId })
  foreach ($c in $cands3) { $null = Invoke-Json 'POST' "/api/design/candidates/$($c.candidateId)/evaluation:freeze" @{} $token }
  return $pid3
}

$p3 = New-UiFixture "UIDecision-act-$(Get-Date -Format 'HHmmss')" "act"
$null = Invoke-Json 'POST' "/api/design/projects/$p3/risks" @{
  riskCode = 'HEPATOTOXICITY'; dimension = 'SAFETY'
  description = 'UI smoke soft risk'; severity = 'LOW'; mustVerify = $false
} $token

$dA = Invoke-Json 'POST' "/api/design/projects/$p3/decisions" @{ decisionNote = 'ui actions' } $token
$rowA = @($dA.Body.data.candidates)[0]
$rowB = @($dA.Body.data.candidates)[1]
Check 'ui.act.setup' ($dA.Status -eq 200 -and $dA.Body.data.selectedCount -ge 1) `
  "selected=$($dA.Body.data.selectedCount) capacity=$($dA.Body.data.capacity)"

# 风险点清单必须出现在候选上，否则页面上的「接受风险」无从点起
$softRisk = @($rowA.risks | Where-Object { $_.riskCode -eq 'HEPATOTOXICITY' })[0]
Check 'ui.candidateRisks' ($null -ne $softRisk -and $softRisk.mustVerify -eq $false) `
  "riskFlags=$(@($rowA.risks).Count) codes=$(@($rowA.risks | ForEach-Object { $_.riskCode }) -join ',')"
Check 'ui.candidateAcceptances' ($null -ne $rowA.acceptances) "acceptances=$(@($rowA.acceptances).Count)"

$acc = Invoke-Json 'POST' "/api/design/decisions/$($dA.Body.data.id)/candidates/$($rowA.candidateId)/accept-risk" `
  @{ riskCode = 'HEPATOTOXICITY'; reason = 'UI: acceptable preclinically' } $token
$accRow = @(@($acc.Body.data.candidates) | Where-Object { $_.candidateId -eq $rowA.candidateId })[0]
Check 'ui.acceptRisk' ($acc.Status -eq 200 -and @($accRow.acceptances).Count -eq 1) `
  "status=$($acc.Status) acceptances=$(@($accRow.acceptances).Count) severity=$($accRow.acceptances[0].severityAtAccept)"

$ovRow = if ($rowB) { $rowB } else { $rowA }
$ovOriginal = $ovRow.outcome
# 先调到「暂缓」（任何候选都允许往更严的方向调），确认改动生效并留痕
$ov = Invoke-Json 'PATCH' "/api/design/decisions/$($dA.Body.data.id)/candidates/$($ovRow.candidateId)" `
  @{ outcome = 'DEFERRED'; overrideReason = 'UI: manual decision' } $token
$manualCount = @($ov.Body.data.candidates | Where-Object { $_.manualOverride }).Count
Check 'ui.override' ($ov.Status -eq 200 -and $manualCount -eq 1) `
  "status=$($ov.Status) code=$($ov.Body.errorCode) manual=$manualCount selected=$($ov.Body.data.selectedCount) deferred=$($ov.Body.data.deferredCount)"

# 再调回原状，让后面的确认测试仍有候选入选
$back = Invoke-Json 'PATCH' "/api/design/decisions/$($dA.Body.data.id)/candidates/$($ovRow.candidateId)" `
  @{ outcome = $ovOriginal; overrideReason = 'UI: restore' } $token
Check 'ui.override.restore' ($back.Status -eq 200 -and $back.Body.data.selectedCount -ge 1) `
  "selected=$($back.Body.data.selectedCount) outcome=$( @($back.Body.data.candidates | Where-Object { $_.candidateId -eq $ovRow.candidateId })[0].outcome )"

# 回放端点：页面「重放比对」按钮的数据来源
$rep = Invoke-Json 'POST' "/api/design/decisions/$($dA.Body.data.id):replay" @{} $token
Check 'ui.replay' ($rep.Status -eq 200 -and $null -ne $rep.Body.data.same -and $null -ne $rep.Body.data.diffs) `
  "same=$($rep.Body.data.same) diffs=$(@($rep.Body.data.diffs).Count)"

# 确认 / 暂缓 / 取消：状态条与按钮文案依赖这些字段
$conf = Invoke-Json 'POST' "/api/design/decisions/$($dA.Body.data.id):confirm" @{} $token
Check 'ui.confirm' ($conf.Status -eq 200 -and $conf.Body.data.status -eq 'CONFIRMED' -and $conf.Body.data.decidedAt) `
  "status=$($conf.Body.data.status) code=$($conf.Body.errorCode) decidedAt=$($conf.Body.data.decidedAt)"

$editAfter = Invoke-Json 'PATCH' "/api/design/decisions/$($dA.Body.data.id)/candidates/$($rowA.candidateId)" `
  @{ outcome = 'REJECTED'; overrideReason = 'UI: after confirm' } $token
Check 'ui.notEditableAfterConfirm' ($editAfter.Status -eq 409 -and $editAfter.Body.errorCode -eq 'DECISION_NOT_EDITABLE') `
  "status=$($editAfter.Status) code=$($editAfter.Body.errorCode)"

$dB = Invoke-Json 'POST' "/api/design/projects/$p3/decisions" @{ decisionNote = 'ui defer' } $token
$def = Invoke-Json 'POST' "/api/design/decisions/$($dB.Body.data.id):defer" @{ reason = 'UI: waiting quota' } $token
Check 'ui.defer' ($def.Status -eq 200 -and $def.Body.data.status -eq 'DEFERRED') "status=$($def.Body.data.status) note=$($def.Body.data.decisionNote)"

$dC = Invoke-Json 'POST' "/api/design/projects/$p3/decisions" @{ decisionNote = 'ui cancel' } $token
$can = Invoke-Json 'POST' "/api/design/decisions/$($dC.Body.data.id):cancel" @{ reason = 'UI: direction changed' } $token
Check 'ui.cancel' ($can.Status -eq 200 -and $can.Body.data.status -eq 'CANCELLED') "status=$($can.Body.data.status)"

# ---- 鍥炴斁鏍￠獙锛堥〉闈㈡寜閽殑琛屼负锛氬啀绠椾竴娆℃瘮 inputSnapshotHash锛?
Write-Output ""
Write-Output ("RESULT  project=$projId decision=$($dd.decisionNo) pass=$pass fail=$fail")

