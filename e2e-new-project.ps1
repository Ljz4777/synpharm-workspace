# 验证前端「新建项目」表单所走的完整链路：
#   建项目（含内联硬约束）→ 按分子形式补指标 → 基线预检八块 → 开轮
# 这条链路完全对应 Projects.vue 的 createProject() + Generation.vue 的 openRound()。
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

# 前端下拉数据源：MODALITY / MOA / 靶点
$mod = Invoke-Json 'GET' '/api/design/library?category=MODALITY&page=1&pageSize=100&enabled=true' $null $token
$small = @($mod.Body.data.list) | Where-Object { $_.code -like '*SMALL*' } | Select-Object -First 1
Check 'dropdown.modality' ($mod.Status -eq 200 -and $null -ne $small.id) "modalId=$($small.id) name=$($small.name)"

$moa = Invoke-Json 'GET' '/api/design/library?category=MOA&page=1&pageSize=100&enabled=true' $null $token
Check 'dropdown.moa' ($moa.Status -eq 200 -and @($moa.Body.data.list).Count -gt 0) "count=$(@($moa.Body.data.list).Count)"

$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1
Check 'dropdown.target' ($null -ne $target.id) "targetId=$($target.id)"

# 与表单完全一致的请求体（ASCII 名，避免 PowerShell 中文编码问题）
$stamp = Get-Date -Format 'HHmmss'
$body = @{
  name           = "NewProjectForm-$stamp"
  modalityId     = $small.id
  minCandidates  = 3
  quotaGpuHours  = 48
  quotaWetLab    = 8
  targets        = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
  constraintRules = @(@{
    ruleType      = 'NUMERIC_RANGE'
    severity      = 'HARD'
    params        = @{ field = 'MolWt'; min = 150; max = 600 }
    weight        = 10
    humanReadable = 'MolWt 150-600 Da'
  })
}
$created = Invoke-Json 'POST' '/api/design/projects' $body $token
Check 'project.create' (($created.Status -eq 200 -or $created.Status -eq 201) -and $created.Body.data.id) "status=$($created.Status) id=$($created.Body.data.id) $($created.Raw)"
$pid2 = $created.Body.data.id

# 表单提交后立刻补指标
$init = Invoke-Json 'POST' "/api/design/projects/$pid2/metrics:init-from-modality" $null $token
$initCount = @($init.Body.data).Count
Check 'metrics.initFromModality' ($init.Status -eq 200 -and $initCount -gt 0) "added=$initCount"

# 落地检查：硬约束是否真的进了约束集
$detail = Invoke-Json 'GET' "/api/design/projects/$pid2" $null $token
Check 'project.activeConstraintSet' ($detail.Body.data.activeConstraintSetId) "constraintSetId=$($detail.Body.data.activeConstraintSetId)"

# 基线八块预检：新建项目必然还不完整，但必须能拿到「缺哪块」
$pv = Invoke-Json 'POST' "/api/design/projects/$pid2/baseline:preview" $null $token
$blockers = @($pv.Body.data.blockers) | Where-Object { $null -ne $_ }
Check 'baseline.preview' ($pv.Status -eq 200) "ready=$($pv.Body.data.ready) blockers=$($blockers.Count)"
if ($blockers.Count -gt 0) {
  $names = ($blockers | ForEach-Object { if ($_ -is [string]) { $_ } else { "$($_.code):$($_.message)" } }) -join ' | '
  Write-Output ("      缺失块: {0}" -f $names)
}

# 生成页缺轮次时的补轮动作
$rd = Invoke-Json 'POST' "/api/design/projects/$pid2/rounds" @{ strategyNote = '在生成页开启' } $token
Check 'round.open' (($rd.Status -eq 200 -or $rd.Status -eq 201) -and $rd.Body.data.id) "status=$($rd.Status) roundId=$($rd.Body.data.id)"

$detail2 = Invoke-Json 'GET' "/api/design/projects/$pid2" $null $token
Check 'project.currentRoundId' ($detail2.Body.data.currentRoundId -eq $rd.Body.data.id) "currentRoundId=$($detail2.Body.data.currentRoundId)"

Write-Output ''
Write-Output ("RESULT  pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
