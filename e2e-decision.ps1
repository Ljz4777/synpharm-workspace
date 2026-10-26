# 阶段 E（E2~E5）端到端验证：证据打包 + 技术性复核 + 业务性否决 + 分桶排序 + 名额分配
#
# 覆盖三条真实路径：
#   ① 无风险声明 → 至少 1 个候选 SELECTED，其余 BACKUP
#   ② 加了「必须验证」风险点后重开决策 → MUST_VERIFY_UNVERIFIED 命中 BLOCK，候选转 REJECTED
#   ③ 否决规则 CRUD：全局只读、项目级可增删改
#
# 另外锁住回放前提：同一批冻结快照两次决策的 inputSnapshotHash 必须一致。
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
    $txt = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    return @{ Status = [int]$r.StatusCode; Body = ($txt | ConvertFrom-Json); Raw = $txt }
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

# ---------------------------------------------------------------- 登录与备料
$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"

$mod = Invoke-Json 'GET' '/api/design/library?category=MODALITY&page=1&pageSize=100' $null $token
$small = @($mod.Body.data.list) | Where-Object { $_.code -like '*SMALL*' } | Select-Object -First 1
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1

$stamp = Get-Date -Format 'HHmmss'
$body = @{
  name            = "Decision-$stamp"
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
}
$created = Invoke-Json 'POST' '/api/design/projects' $body $token
$projId = $created.Body.data.id
$null = Invoke-Json 'POST' "/api/design/projects/$projId/metrics:init-from-modality" @{} $token
$pv = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:preview" @{} $token
$null = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $pv.Body.data.contentHash } $token
$null = Invoke-Json 'POST' "/api/design/projects/$projId/rounds" @{ strategyNote = 'decision' } $token

$gen = Invoke-Json 'POST' "/api/design/projects/$projId/generations" @{
  seeds = @(
    @{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' },
    @{ ref = 'ASP'; smiles = 'CC(=O)Oc1ccccc1C(=O)O' },
    @{ ref = 'CAF'; smiles = 'Cn1cnc2c1c(=O)n(C)c(=O)n2C' }
  )
  seed = 42
  idempotencyKey = "dec-$projId"
} $token
$runId = $gen.Body.data.id

$kept = Invoke-Json 'GET' "/api/design/generations/$runId/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
$cands = @(@($kept.Body.data) | Where-Object { $_.candidateId })
Check 'setup.keptCandidates' ($cands.Count -ge 2) "kept=$($cands.Count)"

# 逐个评估冻结 —— 决策只认冻结快照
$frozen = 0
foreach ($c in $cands) {
  $fz = Invoke-Json 'POST' "/api/design/candidates/$($c.candidateId)/evaluation:freeze" @{} $token
  if ($fz.Status -eq 200 -or $fz.Status -eq 201 -or $fz.Status -eq 202) { $frozen++ }
}
Check 'setup.frozen' ($frozen -eq $cands.Count) "frozen=$frozen/$($cands.Count)"

# ---------------------------------------------------------------- ① 正常决策
$d1 = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = '正常一轮' } $token
Check 'decision.create' (($d1.Status -eq 200 -or $d1.Status -eq 201) -and $d1.Body.data.id) `
  "status=$($d1.Status) decisionNo=$($d1.Body.data.decisionNo)"
$dec1 = $d1.Body.data
Check 'decision.draft' ($dec1.status -eq 'DRAFT') "status=$($dec1.status)"
Check 'decision.replayFingerprint' ($dec1.inputSnapshotHash.Length -eq 64 -and $dec1.ruleVersion -like 'veto-v1:*') `
  "hash=$($dec1.inputSnapshotHash.Substring(0,8)) ruleVersion=$($dec1.ruleVersion)"
Check 'decision.weightsSnapshot' (@($dec1.weightsSnapshot.psobject.Properties).Count -ge 5) `
  "metrics=$(@($dec1.weightsSnapshot.psobject.Properties).Count)"

$rows = @($dec1.candidates)
Check 'decision.candidates' ($rows.Count -ge 2) "rows=$($rows.Count)"
$selected = @($rows | Where-Object { $_.outcome -eq 'SELECTED' })
$backup = @($rows | Where-Object { $_.outcome -eq 'BACKUP' })
Check 'decision.capacity' ($dec1.capacity -ge 1) "capacity=$($dec1.capacity) selected=$($selected.Count) backup=$($backup.Count)"
Check 'decision.selected' ($selected.Count -eq $dec1.capacity) "selected=$($selected.Count)"

# 加权分只来自有数据的指标，能算出来的一定不是 null
$scored = @($rows | Where-Object { $null -ne $_.weightedScore })
Check 'decision.weightedScore' ($scored.Count -ge 1) "scored=$($scored.Count) first=$($scored[0].weightedScore) covers=$($scored[0].coversMetrics)"

# ---------------------------------------------------------------- ② 回放前提：同输入同指纹
$d2 = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = '再算一遍' } $token
Check 'decision.hashStable' ($d2.Body.data.inputSnapshotHash -eq $dec1.inputSnapshotHash) `
  "first=$($dec1.inputSnapshotHash.Substring(0,8)) second=$($d2.Body.data.inputSnapshotHash.Substring(0,8))"

# ---------------------------------------------------------------- ③ 必测风险 → BLOCK 否决
$risk = Invoke-Json 'POST' "/api/design/projects/$projId/risks" @{
  riskCode = 'PTM_HOTSPOT'; dimension = 'STABILITY'
  description = 'CDR 区 N-X-S 糖基化位点风险'; severity = 'MEDIUM'; mustVerify = $true
} $token
Check 'risk.mustVerifyAdded' (($risk.Status -eq 200 -or $risk.Status -eq 201)) "riskCode=PTM_HOTSPOT"

$d3 = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = '带必测风险' } $token
$rows3 = @($d3.Body.data.candidates)
$vetoed = @($rows3 | Where-Object { $_.outcome -eq 'REJECTED' })
$mustVerifyHits = @($rows3 | ForEach-Object { @($_.vetoes) } | Where-Object { $_.ruleCode -eq 'MUST_VERIFY_UNVERIFIED' })
Check 'veto.mustVerify' ($vetoed.Count -eq $rows3.Count) "rejected=$($vetoed.Count)/$($rows3.Count)"
Check 'veto.hitRecorded' ($mustVerifyHits.Count -ge 1) "hits=$($mustVerifyHits.Count) severity=$($mustVerifyHits[0].severity)"
Check 'veto.filterReason' ($vetoed.Count -gt 0 -and $vetoed[0].rejectReason -eq 'VETO_MUST_VERIFY_MISSING') `
  "rejectReason=$($vetoed[0].rejectReason)"
Check 'veto.noViableWarning' (@($d3.Body.data.warnings) -contains 'NO_VIABLE_CANDIDATE：全部候选均被否决或复核不通过' -or @($d3.Body.data.warnings).Count -gt 0) `
  "warnings=$(@($d3.Body.data.warnings).Count)"

# ---------------------------------------------------------------- ④ 详情与历史
$detail = Invoke-Json 'GET' "/api/design/decisions/$($dec1.id)" $null $token
Check 'decision.get' ($detail.Status -eq 200 -and @($detail.Body.data.candidates).Count -ge 2) `
  "candidates=$(@($detail.Body.data.candidates).Count)"
$hist = Invoke-Json 'GET' "/api/design/projects/$projId/decisions" $null $token
Check 'decision.history' (@($hist.Body.data).Count -ge 3) "records=$(@($hist.Body.data).Count)"

# ---------------------------------------------------------------- ⑤ 否决规则 CRUD
$rules = Invoke-Json 'GET' "/api/design/projects/$projId/decision-veto-rules" $null $token
$global = @($rules.Body.data | Where-Object { $_.global -eq $true })
Check 'vetoRule.list' ($rules.Status -eq 200 -and $global.Count -ge 6) "total=$(@($rules.Body.data).Count) global=$($global.Count)"

$newRule = Invoke-Json 'POST' "/api/design/projects/$projId/decision-veto-rules" @{
  ruleCode = 'FTO_RISK_HIGH'; severity = 'WARN'; thresholdNum = 0.9
  paramsJson = '{"metricField":"fto_similarity_max"}'
  humanReadable = '本项目自定义：FTO 相似度超过 0.9 仅提示'
} $token
Check 'vetoRule.create' (($newRule.Status -eq 200 -or $newRule.Status -eq 201) -and $newRule.Body.data.id) `
  "id=$($newRule.Body.data.id) severity=$($newRule.Body.data.severity)"
$ruleId = $newRule.Body.data.id

$upd = Invoke-Json 'PUT' "/api/design/decision-veto-rules/$ruleId" @{
  ruleCode = 'FTO_RISK_HIGH'; severity = 'BLOCK'; thresholdNum = 0.88
  paramsJson = '{"metricField":"fto_similarity_max"}'
  humanReadable = '本项目自定义：FTO 相似度超过 0.88 直接否决'
  enabled = $true
} $token
Check 'vetoRule.update' ($upd.Status -eq 200 -and $upd.Body.data.severity -eq 'BLOCK') "severity=$($upd.Body.data.severity)"

$globalId = $global[0].id
$immutable = Invoke-Json 'PUT' "/api/design/decision-veto-rules/$globalId" @{
  ruleCode = 'MUST_VERIFY_UNVERIFIED'; severity = 'WARN'; humanReadable = '试图改全局规则'
} $token
Check 'vetoRule.globalImmutable' ($immutable.Status -eq 409 -and $immutable.Body.errorCode -eq 'DECISION_VETO_RULE_GLOBAL_IMMUTABLE') `
  "status=$($immutable.Status) code=$($immutable.Body.errorCode)"

$del = Invoke-Json 'DELETE' "/api/design/decision-veto-rules/$ruleId" $null $token
Check 'vetoRule.delete' ($del.Status -eq 200) "status=$($del.Status)"
$gone = Invoke-Json 'PUT' "/api/design/decision-veto-rules/$ruleId" @{
  ruleCode = 'FTO_RISK_HIGH'; severity = 'WARN'; humanReadable = '已删除的规则'
} $token
Check 'vetoRule.deletedNotFound' ($gone.Status -eq 404) "status=$($gone.Status)"

# ---------------------------------------------------------------- ⑥ 拒绝路径
$noFreezeProject = Invoke-Json 'POST' '/api/design/projects' $body $token
$npId = $noFreezeProject.Body.data.id
$noBaseline = Invoke-Json 'POST' "/api/design/projects/$npId/decisions" @{} $token
Check 'guard.noBaseline' ($noBaseline.Status -eq 404 -and $noBaseline.Body.errorCode -eq 'BASELINE_NOT_FOUND') `
  "status=$($noBaseline.Status) code=$($noBaseline.Body.errorCode)"

# ================================================================ D6 人工调整
$sel = @($rows | Where-Object { $_.outcome -eq 'SELECTED' })[0]
$bak = @($rows | Where-Object { $_.outcome -eq 'BACKUP' })[0]

$noReason = Invoke-Json 'PATCH' "/api/design/decisions/$($dec1.id)/candidates/$($bak.candidateId)" @{ outcome = 'SELECTED' } $token
Check 'd6.override.reasonRequired' ($noReason.Status -eq 400) "status=$($noReason.Status) code=$($noReason.Body.errorCode)"

$overridden = Invoke-Json 'PATCH' "/api/design/decisions/$($dec1.id)/candidates/$($bak.candidateId)" `
  @{ outcome = 'SELECTED'; overrideReason = '备选与首选同分，都要做' } $token
$ovRows = @($overridden.Body.data.candidates)
$ovRow = @($ovRows | Where-Object { $_.candidateId -eq $bak.candidateId })[0]
Check 'd6.override.topromote' ($overridden.Status -eq 200 -and $ovRow.outcome -eq 'SELECTED') "outcome=$($ovRow.outcome)"
Check 'd6.override.reasonSaved' ($ovRow.manualOverride -eq $true -and $ovRow.overrideReason -eq '备选与首选同分，都要做') `
  "manualOverride=$($ovRow.manualOverride) reason=$($ovRow.overrideReason)"
Check 'd6.override.countsRecalculated' ($overridden.Body.data.selectedCount -eq 2 -and $overridden.Body.data.backupCount -eq 0) `
  "selected=$($overridden.Body.data.selectedCount) backup=$($overridden.Body.data.backupCount)"

$restored = Invoke-Json 'PATCH' "/api/design/decisions/$($dec1.id)/candidates/$($bak.candidateId)" `
  @{ outcome = 'BACKUP'; overrideReason = '还是按名额走' } $token
Check 'd6.override.restore' ($restored.Status -eq 200 -and $restored.Body.data.selectedCount -eq 1) `
  "selected=$($restored.Body.data.selectedCount)"

# 否决不可绕过：全灭那条决策里的候选是 FAIL 桶，改成入选必须被拒
$failRow = @($rows3 | Where-Object { $_.bucket -eq 'FAIL' })[0]
$illegal = Invoke-Json 'PATCH' "/api/design/decisions/$($d3.Body.data.id)/candidates/$($failRow.candidateId)" `
  @{ outcome = 'SELECTED'; overrideReason = '我觉得可以' } $token
Check 'd6.override.blockedByVeto' ($illegal.Status -eq 409 -and $illegal.Body.errorCode -eq 'DECISION_BLOCKED_BY_VETO') `
  "status=$($illegal.Status) code=$($illegal.Body.errorCode)"

# ================================================================ D7 风险接受
$mustVerifyAccept = Invoke-Json 'POST' "/api/design/decisions/$($d3.Body.data.id)/candidates/$($failRow.candidateId)/accept-risk" `
  @{ riskCode = 'PTM_HOTSPOT'; reason = '想接受必测项' } $token
Check 'd7.accept.mustVerifyForbidden' ($mustVerifyAccept.Status -eq 409 -and $mustVerifyAccept.Body.errorCode -eq 'RISK_ACCEPTANCE_FORBIDDEN') `
  "status=$($mustVerifyAccept.Status) code=$($mustVerifyAccept.Body.errorCode)"

# 加一个「非必测」风险，再新开一次决策，它才会进证据包
$softRisk = Invoke-Json 'POST' "/api/design/projects/$projId/risks" @{
  riskCode = 'HEPATOTOXICITY'; dimension = 'SAFETY'
  description = '肝毒性提示（非必测）'; severity = 'LOW'; mustVerify = $false
} $token
Check 'd7.setup.softRisk' (($softRisk.Status -eq 200 -or $softRisk.Status -eq 201)) "riskCode=HEPATOTOXICITY"

$d4 = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = '风险接受用' } $token
$d4Row = @($d4.Body.data.candidates)[0]
$accepted = Invoke-Json 'POST' "/api/design/decisions/$($d4.Body.data.id)/candidates/$($d4Row.candidateId)/accept-risk" `
  @{ riskCode = 'HEPATOTOXICITY'; reason = '该风险可在临床前阶段观察，先接受' } $token
$accRow = @(@($accepted.Body.data.candidates) | Where-Object { $_.candidateId -eq $d4Row.candidateId })[0]
Check 'd7.accept.ok' ($accepted.Status -eq 200 -and @($accRow.acceptances).Count -eq 1) `
  "status=$($accepted.Status) acceptances=$(@($accRow.acceptances).Count)"
Check 'd7.accept.recorded' ($accRow.acceptances[0].riskCode -eq 'HEPATOTOXICITY' -and $accRow.acceptances[0].severityAtAccept -eq 'LOW') `
  "risk=$($accRow.acceptances[0].riskCode) severity=$($accRow.acceptances[0].severityAtAccept)"

$unknownRisk = Invoke-Json 'POST' "/api/design/decisions/$($d4.Body.data.id)/candidates/$($d4Row.candidateId)/accept-risk" `
  @{ riskCode = 'NOT_A_RISK'; reason = '凭空接受' } $token
Check 'd7.accept.unknownRisk' ($unknownRisk.Status -eq 404) "status=$($unknownRisk.Status)"

# ================================================================ D8 回放 / 确认 / 暂缓 / 取消
# 这一段单独起一个干净项目：上面那个项目已经加过 must-verify 风险，所有候选都被否，
# 用它会「因为没候选可确认」而测不到回放/确认本身。
function New-DecisionFixture([string]$name, [string]$tag) {
  $created2 = Invoke-Json 'POST' '/api/design/projects' @{
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
  $pid2 = $created2.Body.data.id
  $null = Invoke-Json 'POST' "/api/design/projects/$pid2/metrics:init-from-modality" @{} $token
  $pv2 = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:preview" @{} $token
  $null = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:freeze" @{ expectedContentHash = $pv2.Body.data.contentHash } $token
  $null = Invoke-Json 'POST' "/api/design/projects/$pid2/rounds" @{ strategyNote = 'd8' } $token
  $gen2 = Invoke-Json 'POST' "/api/design/projects/$pid2/generations" @{
    seeds = @(
      @{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' },
      @{ ref = 'ASP'; smiles = 'CC(=O)Oc1ccccc1C(=O)O' }
    )
    seed = 42
    idempotencyKey = "d8-$tag-$pid2"
  } $token
  $kept2 = Invoke-Json 'GET' "/api/design/generations/$($gen2.Body.data.id)/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
  $cands2 = @(@($kept2.Body.data) | Where-Object { $_.candidateId })
  foreach ($c in $cands2) { $null = Invoke-Json 'POST' "/api/design/candidates/$($c.candidateId)/evaluation:freeze" @{} $token }
  return $pid2
}

$p2 = New-DecisionFixture "Decision-D8-$stamp" "d8"
Check 'd8.setup.fixture' ($p2 -gt 0) "project=$p2"

$dA = Invoke-Json 'POST' "/api/design/projects/$p2/decisions" @{ decisionNote = 'D8 确认用' } $token
Check 'd8.setup.decisionA' (($dA.Status -eq 200 -or $dA.Status -eq 201) -and $dA.Body.data.selectedCount -ge 1) `
  "selected=$($dA.Body.data.selectedCount) capacity=$($dA.Body.data.capacity)"
$aRows = @($dA.Body.data.candidates)
$aSel = @($aRows | Where-Object { $_.outcome -eq 'SELECTED' })[0]

$replayOk = Invoke-Json 'POST' "/api/design/decisions/$($dA.Body.data.id):replay" @{} $token
Check 'd8.replay.same' ($replayOk.Status -eq 200 -and $replayOk.Body.data.same -eq $true) `
  "same=$($replayOk.Body.data.same) diffs=$(@($replayOk.Body.data.diffs).Count) added=$(@($replayOk.Body.data.addedCandidates).Count)"

$confirmed = Invoke-Json 'POST' "/api/design/decisions/$($dA.Body.data.id):confirm" @{} $token
Check 'd8.confirm.ok' ($confirmed.Status -eq 200 -and $confirmed.Body.data.status -eq 'CONFIRMED') `
  "status=$($confirmed.Body.data.status) decidedAt=$($confirmed.Body.data.decidedAt)"

$editAfterConfirm = Invoke-Json 'PATCH' "/api/design/decisions/$($dA.Body.data.id)/candidates/$($aSel.candidateId)" `
  @{ outcome = 'REJECTED'; overrideReason = '确认后想改' } $token
Check 'd8.confirm.immutable' ($editAfterConfirm.Status -eq 409 -and $editAfterConfirm.Body.errorCode -eq 'DECISION_NOT_EDITABLE') `
  "status=$($editAfterConfirm.Status) code=$($editAfterConfirm.Body.errorCode)"

$confirmAgain = Invoke-Json 'POST' "/api/design/decisions/$($dA.Body.data.id):confirm" @{} $token
Check 'd8.confirm.twiceBlocked' ($confirmAgain.Status -eq 409 -and $confirmAgain.Body.errorCode -eq 'DECISION_NOT_EDITABLE') `
  "status=$($confirmAgain.Status) code=$($confirmAgain.Body.errorCode)"

$confirmNoViable = Invoke-Json 'POST' "/api/design/decisions/$($d3.Body.data.id):confirm" @{} $token
Check 'd8.confirm.noViable' ($confirmNoViable.Status -eq 409 -and $confirmNoViable.Body.errorCode -eq 'NO_VIABLE_CANDIDATE') `
  "status=$($confirmNoViable.Status) code=$($confirmNoViable.Body.errorCode)"

$dB = Invoke-Json 'POST' "/api/design/projects/$p2/decisions" @{ decisionNote = '暂缓用' } $token
$deferNoReason = Invoke-Json 'POST' "/api/design/decisions/$($dB.Body.data.id):defer" @{} $token
Check 'd8.defer.reasonRequired' ($deferNoReason.Status -eq 400) "status=$($deferNoReason.Status)"

$deferred = Invoke-Json 'POST' "/api/design/decisions/$($dB.Body.data.id):defer" @{ reason = '湿实验额度待批' } $token
Check 'd8.defer.ok' ($deferred.Status -eq 200 -and $deferred.Body.data.status -eq 'DEFERRED') `
  "status=$($deferred.Body.data.status) note=$($deferred.Body.data.decisionNote)"

$dC = Invoke-Json 'POST' "/api/design/projects/$p2/decisions" @{ decisionNote = '取消用' } $token
$cancelled = Invoke-Json 'POST' "/api/design/decisions/$($dC.Body.data.id):cancel" @{ reason = '方向调整' } $token
Check 'd8.cancel.ok' ($cancelled.Status -eq 200 -and $cancelled.Body.data.status -eq 'CANCELLED') `
  "status=$($cancelled.Body.data.status)"

# REPLAY_MISMATCH：先建决策，再改证据（加一条非必测风险），确认必须被拒
$dD = Invoke-Json 'POST' "/api/design/projects/$p2/decisions" @{ decisionNote = '回放不一致用' } $token
Check 'd8.setup.decisionD' (($dD.Status -eq 200 -or $dD.Status -eq 201) -and $dD.Body.data.selectedCount -ge 1) `
  "selected=$($dD.Body.data.selectedCount)"
$null = Invoke-Json 'POST' "/api/design/projects/$p2/risks" @{
  riskCode = 'HEPATOTOXICITY'; dimension = 'SAFETY'
  description = '肝毒性提示（非必测）'; severity = 'LOW'; mustVerify = $false
} $token
$mismatch = Invoke-Json 'POST' "/api/design/decisions/$($dD.Body.data.id):confirm" @{} $token
Check 'd8.confirm.replayMismatch' ($mismatch.Status -eq 409 -and $mismatch.Body.errorCode -eq 'REPLAY_MISMATCH') `
  "status=$($mismatch.Status) code=$($mismatch.Body.errorCode)"

$replayDiff = Invoke-Json 'POST' "/api/design/decisions/$($dD.Body.data.id):replay" @{} $token
Check 'd8.replay.notSame' ($replayDiff.Status -eq 200 -and $replayDiff.Body.data.same -eq $false `
    -and $replayDiff.Body.data.storedInputHash -ne $replayDiff.Body.data.recomputedInputHash) `
  "same=$($replayDiff.Body.data.same) stored=$($replayDiff.Body.data.storedInputHash.Substring(0,8)) now=$($replayDiff.Body.data.recomputedInputHash.Substring(0,8))"

Write-Output ''
Write-Output ("RESULT  project={0} decision={1} candidates={2}" -f $projId, $dec1.decisionNo, $rows.Count)
Write-Output ("RESULT  pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
