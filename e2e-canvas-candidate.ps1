# 二维画布 → 候选 → 评估冻结（手工候选入口）的端到端验证
#
# 覆盖：
#   ① 合法 SMILES → 建出候选（结构/InChIKey/分子式/指标都由计算层实算）
#   ② 同分子重复建 → 不重复落库，返回已有那条（existed=true）
#   ③ 非法 SMILES → 400 INVALID_SMILES，不落库
#   ④ 项目没开轮次 → 404 且说明「候选必须属于某一轮」
#   ⑤ 建出来的候选能直接评估冻结，并进入决策候选集
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

$mod = Invoke-Json 'GET' '/api/design/library?category=MODALITY&page=1&pageSize=100' $null $token
$small = @($mod.Body.data.list) | Where-Object { $_.code -like '*SMALL*' } | Select-Object -First 1
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1

$stamp = Get-Date -Format 'HHmmss'
$proj = Invoke-Json 'POST' '/api/design/projects' @{
  name            = "Canvas-$stamp"
  modalityId      = $small.id
  moaCode         = 'INHIBITOR'
  minCandidates   = 2
  quotaGpuHours   = 24
  quotaWetLab     = 4
  targets         = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
  constraintRules = @(@{
    ruleType = 'NUMERIC_RANGE'; severity = 'HARD'
    params = @{ field = 'MolWt'; min = 150; max = 600 }
    weight = 10; humanReadable = 'MW 150-600'
  })
} $token
$projId = $proj.Body.data.id
$null = Invoke-Json 'POST' "/api/design/projects/$projId/metrics:init-from-modality" @{} $token
$pv = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:preview" @{} $token
$null = Invoke-Json 'POST' "/api/design/projects/$projId/baseline:freeze" @{ expectedContentHash = $pv.Body.data.contentHash } $token

# ---------------------------------------------------------------- ④ 没开轮次
$noRound = Invoke-Json 'POST' "/api/design/projects/$projId/candidates" @{ smiles = 'CC(=O)Oc1ccccc1C(=O)O' } $token
Check 'canvas.needsRound' ($noRound.Status -eq 404 -and $noRound.Body.errorCode -eq 'ROUND_NOT_FOUND') `
  "status=$($noRound.Status) code=$($noRound.Body.errorCode) msg=$($noRound.Body.message)"

$null = Invoke-Json 'POST' "/api/design/projects/$projId/rounds" @{ strategyNote = 'canvas' } $token

# ---------------------------------------------------------------- ① 合法 SMILES
$aspirin = Invoke-Json 'POST' "/api/design/projects/$projId/candidates" @{
  smiles = 'CC(=O)Oc1ccccc1C(=O)O'; moleculeName = "画布阿司匹林-$stamp"
} $token
Check 'canvas.create' (($aspirin.Status -eq 200 -or $aspirin.Status -eq 201) -and $aspirin.Body.data.candidateId) `
  "status=$($aspirin.Status) id=$($aspirin.Body.data.candidateId) code=$($aspirin.Body.errorCode)"
$c1 = $aspirin.Body.data
Check 'canvas.inchikeyFromEngine' ($c1.inchikey -eq 'BSYNRYMUTXBXSQ-UHFFFAOYSA-N') "inchikey=$($c1.inchikey)"
Check 'canvas.formulaFromEngine' ($c1.formula -eq 'C9H8O4') "formula=$($c1.formula)"
Check 'canvas.metricsPersisted' ($null -ne $c1.mw -and $c1.mw -gt 179 -and $c1.mw -lt 181 -and $c1.metricCount -ge 10) `
  "mw=$($c1.mw) logp=$($c1.logp) qed=$($c1.qed) metricCount=$($c1.metricCount)"
Check 'canvas.originType' ($c1.originType -eq 'IMPORTED') "originType=$($c1.originType)"
Check 'canvas.existedFalse' ($c1.existed -eq $false) "existed=$($c1.existed)"

# ---------------------------------------------------------------- ② 同分子不重复建
$dup = Invoke-Json 'POST' "/api/design/projects/$projId/candidates" @{ smiles = 'OC(=O)c1ccccc1OC(C)=O' } $token
Check 'canvas.dedupByInchikey' ($dup.Status -eq 200 -and $dup.Body.data.candidateId -eq $c1.candidateId -and $dup.Body.data.existed -eq $true) `
  "id=$($dup.Body.data.candidateId) existed=$($dup.Body.data.existed)"

# ---------------------------------------------------------------- ③ 非法 SMILES
$bad = Invoke-Json 'POST' "/api/design/projects/$projId/candidates" @{ smiles = '这不是一个分子' } $token
Check 'canvas.invalidSmiles' ($bad.Status -eq 400 -and $bad.Body.errorCode -eq 'INVALID_SMILES') `
  "status=$($bad.Status) code=$($bad.Body.errorCode) msg=$($bad.Body.message)"

# ---------------------------------------------------------------- ⑤ 直接评估冻结 + 进决策
$freeze = Invoke-Json 'POST' "/api/design/candidates/$($c1.candidateId)/evaluation:freeze" @{} $token
Check 'canvas.freeze' ($freeze.Status -eq 200 -and $freeze.Body.data.freezeNo) `
  "freezeNo=$($freeze.Body.data.freezeNo) coverage=$($freeze.Body.data.weightCoverage) hardPassed=$($freeze.Body.data.hardPassed)"

$second = Invoke-Json 'POST' "/api/design/projects/$projId/candidates" @{ smiles = 'Cn1cnc2c1c(=O)n(C)c(=O)n2C' } $token
$c2 = $second.Body.data
$null = Invoke-Json 'POST' "/api/design/candidates/$($c2.candidateId)/evaluation:freeze" @{} $token

$decision = Invoke-Json 'POST' "/api/design/projects/$projId/decisions" @{ decisionNote = 'canvas candidates' } $token
$candIds = @($decision.Body.data.candidates | ForEach-Object { $_.candidateId })
Check 'canvas.reachesDecision' ($decision.Status -eq 200 -and ($candIds -contains $c1.candidateId) -and ($candIds -contains $c2.candidateId)) `
  "decision=$($decision.Body.data.decisionNo) candidates=[$($candIds -join ',')]"

Write-Output ''
Write-Output ("RESULT  project={0} canvasCandidates={1},{2} pass={3} fail={4}" -f $projId, $c1.candidateId, $c2.candidateId, $pass, $fail)
if ($fail -gt 0) { exit 1 }
