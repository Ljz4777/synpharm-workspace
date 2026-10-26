# 单分子精修台（/design）数据链验证
# 页面依赖四级真实数据：项目 → 基线快照 → 生成批次 → 配额保留候选（带 smiles + inchikey）
# 再往下是 评估冻结 / 缺口 / 版本树。此脚本按页面同样的顺序逐级取一遍。
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

$ready = $false
for ($i = 0; $i -lt 30; $i++) {
  try {
    $probe = Invoke-WebRequest -Uri "$GW/actuator/health" -UseBasicParsing -TimeoutSec 3
    if ($probe.StatusCode -eq 200) { $ready = $true; break }
  } catch { }
  Start-Sleep -Seconds 4
}
if (-not $ready) { Write-Output 'GATEWAY NOT READY'; exit 2 }

$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"

# --- 造一个「冻结过基线、生成过候选」的项目（精修台的前置条件） ---
$mod = Invoke-Json 'GET' '/api/design/library?category=MODALITY&page=1&pageSize=100' $null $token
$small = @($mod.Body.data.list) | Where-Object { $_.code -like '*SMALL*' } | Select-Object -First 1
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1

$stamp = Get-Date -Format 'HHmmss'
$body = @{
  name           = "Workbench-$stamp"
  modalityId     = $small.id
  moaCode        = 'INHIBITOR'
  minCandidates  = 3
  quotaGpuHours  = 48
  quotaWetLab    = 8
  targets        = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
  constraintRules = @(@{
    ruleType      = 'NUMERIC_RANGE'
    severity      = 'HARD'
    params        = @{ field = 'MolWt'; min = 150; max = 600 }
    weight        = 10
    humanReadable = 'MW 150-600'
  })
}
$created = Invoke-Json 'POST' '/api/design/projects' $body $token
$pid2 = $created.Body.data.id
$null = Invoke-Json 'POST' "/api/design/projects/$pid2/metrics:init-from-modality" $null $token
$pv = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:preview" $null $token
$fz = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:freeze" @{ expectedContentHash = $pv.Body.data.contentHash } $token
Check 'setup.freeze' (($fz.Status -eq 200 -or $fz.Status -eq 201)) "snapshot=$($fz.Body.data.snapshotNo)"
$null = Invoke-Json 'POST' "/api/design/projects/$pid2/rounds" @{ strategyNote = 'workbench' } $token

$gen = Invoke-Json 'POST' "/api/design/projects/$pid2/generations" @{
  seeds          = @(@{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' })
  seed           = 42
  idempotencyKey = "wb-$pid2"
} $token
Check 'setup.generate' (($gen.Status -eq 200 -or $gen.Status -eq 201 -or $gen.Status -eq 202) -and $gen.Body.data.id) "runId=$($gen.Body.data.id)"

# --- 页面第 1 级：项目列表 ---
$plist = Invoke-Json 'GET' '/api/design/projects?page=1&pageSize=50' $null $token
Check 'ui.projects' ($plist.Status -eq 200 -and @($plist.Body.data.list).Count -gt 0) "count=$(@($plist.Body.data.list).Count)"

# --- 第 2 级：冻结基线（硬约束真值来源） ---
$snap = Invoke-Json 'GET' "/api/design/projects/$pid2/baseline" $null $token
$items = @($snap.Body.data.hardRules.items)
$p0 = $null
if ($items.Count -gt 0) {
  $raw = $items[0].paramsJson
  if ($raw -is [string]) { $p0 = $raw | ConvertFrom-Json } else { $p0 = $raw }
}
Check 'ui.baseline.hardRules' ($snap.Status -eq 200 -and $items.Count -gt 0 -and $p0.field -eq 'MolWt') `
  "items=$($items.Count) field=$($p0.field) min=$($p0.min) max=$($p0.max)"

# --- 第 3 级：批次列表 ---
$runs = Invoke-Json 'GET' "/api/design/generations?projectId=$pid2&page=1&pageSize=20" $null $token
$runId = @($runs.Body.data.list)[0].id
Check 'ui.runs' ($runs.Status -eq 200 -and $runId) "count=$(@($runs.Body.data.list).Count) firstRun=$runId"

# --- 第 4 级：配额保留候选（smiles + inchikey 是精修台的输入） ---
$kept = Invoke-Json 'GET' "/api/design/generations/$runId/candidates?stage=S7_QUOTA&outcome=KEPT" $null $token
$keptRows = @(@($kept.Body.data) | Where-Object { $_.candidateId })
$first = $keptRows | Select-Object -First 1
Check 'ui.kept' ($kept.Status -eq 200 -and $keptRows.Count -gt 0) "kept=$($keptRows.Count)"
Check 'ui.kept.hasSmiles' ($first.smiles -and $first.candidateId) "candidateId=$($first.candidateId) smiles=$($first.smiles)"
Check 'ui.kept.smilesUsable' ($first.smiles.Length -gt 3) "smilesLen=$($first.smiles.Length)"

# --- 第 5 级：单分子上下文（评估 / 缺口 / 版本树） ---
$candId = $first.candidateId
$ez = Invoke-Json 'POST' "/api/design/candidates/$candId/evaluation:freeze" @{} $token
Check 'ui.eval.freeze' ($ez.Status -eq 200 -or $ez.Status -eq 201 -or $ez.Status -eq 202) "freezeNo=$($ez.Body.data.freezeNo) coverage=$($ez.Body.data.weightCoverage)"

$gp = Invoke-Json 'POST' "/api/design/candidates/$candId/gaps:diagnose" @{} $token
Check 'ui.gaps' ($gp.Status -eq 200 -or $gp.Status -eq 201 -or $gp.Status -eq 202) "gaps=$(@($gp.Body.data.gaps).Count) unfixable=$(@($gp.Body.data.unfixable).Count) actions=$(@($gp.Body.data.actions).Count)"

$tr = Invoke-Json 'GET' "/api/design/candidates/$candId/tree" $null $token
Check 'ui.tree' ($tr.Status -eq 200 -and @($tr.Body.data.nodes).Count -gt 0) "nodes=$(@($tr.Body.data.nodes).Count)"

$acts = @(@($gp.Body.data.actions) | Where-Object { $_ -and $_.actionCode })
if ($acts.Count -gt 0) {
  $im = Invoke-Json 'POST' "/api/design/candidates/$candId/actions:preview" @{ actionCode = $acts[0].actionCode } $token
  Check 'ui.impact' ($im.Status -eq 200 -or $im.Status -eq 201 -or $im.Status -eq 202) "action=$($acts[0].actionCode) guard=$($im.Body.data.guardResult)"
} else {
  Write-Output 'SKIP  ui.impact  本批候选没有可匹配动作'
}

Write-Output ''
Write-Output ("RESULT  project={0} run={1} candidate={2}" -f $pid2, $runId, $candId)
Write-Output ("RESULT  pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
