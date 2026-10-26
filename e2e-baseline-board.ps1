# 需求基线「八块看板」渲染验证
# 看板展开后是按 block 渲染的人话（靶点卡片 / 约束表 / 指标表 / 配额键值对），
# 前提是三个形状成立：snapshot.targets 等字段是 {items:[...]} 包装、字段名与组件一致、
# 中文不乱码。此脚本按组件读取的方式逐项断言。
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
    return @{ Status = [int]$resp.StatusCode; Body = ($sr.ReadToEnd() | ConvertFrom-Json) }
  }
}

$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"

$mod = Invoke-Json 'GET' '/api/design/library?category=MODALITY&page=1&pageSize=100' $null $token
$small = @($mod.Body.data.list) | Where-Object { $_.code -like '*SMALL*' } | Select-Object -First 1
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1

$stamp = Get-Date -Format 'HHmmss'
$body = @{
  name           = "Board-$stamp"
  modalityId     = $small.id
  moaCode        = 'INHIBITOR'
  minCandidates  = 3
  quotaGpuHours  = 48
  quotaWetLab    = 8
  targets        = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
  constraintRules = @(@{
    ruleType = 'NUMERIC_RANGE'; severity = 'HARD'
    params = @{ field = 'MolWt'; min = 150; max = 600 }
    weight = 10; humanReadable = '分子量 150~600 Da'
  })
}
$created = Invoke-Json 'POST' '/api/design/projects' $body $token
$pid2 = $created.Body.data.id
$null = Invoke-Json 'POST' "/api/design/projects/$pid2/metrics:init-from-modality" @{} $token
$pv = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:preview" @{} $token
$null = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:freeze" @{ expectedContentHash = $pv.Body.data.contentHash } $token

$snap = Invoke-Json 'GET' "/api/design/projects/$pid2/baseline" $null $token
Check 'snapshot.ok' ($snap.Status -eq 200 -and $snap.Body.data.id) "snapshotNo=$($snap.Body.data.snapshotNo)"

$s = $snap.Body.data

# --- 块1 靶点：组件按 targets.items[] 渲染卡片 ---
$t0 = @($s.targets.items)[0]
Check 'block.targets.items' ($null -ne $t0.name) "name=$($t0.name) primary=$($t0.isPrimary)"
Check 'block.targets.chinese' ([bool]$t0.targetType -and -not $t0.targetType.Contains('?')) "targetType=$($t0.targetType)"
Check 'block.targets.moa' ([bool]$s.targets.moaCode) "moaCode=$($s.targets.moaCode)"

# --- 块2 结构：structures[] + pockets[] ---
$struct = @($s.structure.structures)
$pockets = @($s.structure.pockets)
Check 'block.structure.structures' ($struct.Count -gt 0) "structures=$($struct.Count) first=$($struct[0].pdbId)/$($struct[0].source)"
Check 'block.structure.pockets' ($pockets.Count -gt 0) "pockets=$($pockets.Count) first=$($pockets[0].pocketNo)/$($pockets[0].method)"

# --- 块3 分子形式：单个字符串 ---
Check 'block.modality' ($s.modalityCode -eq 'SMALL_MOLECULE') "modalityCode=$($s.modalityCode)"

# --- 块4/5 约束：items[] 且 humanReadable 是中文字话、不带问号 ---
$h0 = @($s.hardRules.items)[0]
Check 'block.hardRules.items' ($null -ne $h0.ruleId) "ruleId=$($h0.ruleId) type=$($h0.ruleType)"
Check 'block.hardRules.human' ([bool]$h0.humanReadable -and -not $h0.humanReadable.Contains('?')) "humanReadable=$($h0.humanReadable)"
Check 'block.hardRules.policy' ($h0.boundaryPolicy -eq 'STRICT') "boundaryPolicy=$($h0.boundaryPolicy)"

# --- 块6 指标：metricName 中文、unit 中文、weight 是数字 ---
$ms = @($s.metrics.items)
$m0 = $ms[0]
Check 'block.metrics.items' ($ms.Count -ge 10) "items=$($ms.Count)"
Check 'block.metrics.chinese' ([bool]$m0.metricName -and -not $m0.metricName.Contains('?') -and -not $m0.metricName.Contains([char]0x00E5)) "metricName=$($m0.metricName)"
$weightSum = ($ms | Measure-Object -Property weight -Sum).Sum
Check 'block.metrics.weights' ($weightSum -gt 0) "weightSum=$weightSum"

# --- 块7 配额：扁平字段 ---
$q = $s.quota
Check 'block.quota' ($null -ne $q.minCandidates -and $null -ne $q.gpuHours) "minCandidates=$($q.minCandidates) gpu=$($q.gpuHours) wetLab=$($q.wetLab) mode=$($q.capacityMode)"

# --- 块8 风险：items[]（本次为空，形状要对） ---
Check 'block.risks.shape' ($null -ne $s.risks.items) "risks=$(@($s.risks.items).Count)"

Write-Output ''
Write-Output ("RESULT  project={0} snapshot={1}" -f $pid2, $s.snapshotNo)
Write-Output ("RESULT  pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
