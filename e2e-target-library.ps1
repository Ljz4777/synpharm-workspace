# 靶点库（/targets）数据链验证
# 页面已从 @/data/mockResults 切到 TargetController。此脚本断言页面真正依赖的形状：
#   列表 → 结构 → 口袋（三层都要有），以及「可立项性」判断所依据的字段。
# 重点断言 TP53 无结构 —— 那是「不可立项」这条提示的数据依据，不是文案编的。
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

$res = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=200' $null $token
$list = @($res.Body.data.list)
Check 'targets.list' ($res.Status -eq 200 -and $list.Count -ge 3) "count=$($list.Count) total=$($res.Body.data.total)"

# --- 字段完整性：页面右栏「基本信息 / 分子特征」逐项读这些字段 ---
$egfr = $list | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1
Check 'target.fields' ([bool]$egfr.id -and [bool]$egfr.uniprotId -and [bool]$egfr.geneName) `
  "id=$($egfr.id) uniprot=$($egfr.uniprotId) gene=$($egfr.geneName) type=$($egfr.targetType)"
Check 'target.chinese' (-not ([string]$egfr.chineseName).Contains('?')) "chineseName=$($egfr.chineseName)"
Check 'target.meta' ([bool]$egfr.family -and [bool]$egfr.pathway -and [bool]$egfr.diseaseArea) `
  "family=$($egfr.family) pathway=$($egfr.pathway) area=$($egfr.diseaseArea)"

# --- 结构层：基线块2 的唯一来源 ---
$egfrStructs = @($egfr.structures)
Check 'structure.exists' ($egfrStructs.Count -ge 1) "structures=$($egfrStructs.Count)"
$withPdb = $egfrStructs | Where-Object { $_.pdbId } | Select-Object -First 1
$predicted = $egfrStructs | Where-Object { $_.source -eq 'ALPHAFOLD' } | Select-Object -First 1
Check 'structure.pdb' ([bool]$withPdb.pdbId) "pdbId=$($withPdb.pdbId) chain=$($withPdb.chainId) res=$($withPdb.resolution)"
Check 'structure.predicted' ([bool]$predicted.id) "alphaFoldStructureId=$($predicted.id)"

# --- 口袋层：对接类指标的前提 ---
$pockets = @($withPdb.pockets)
Check 'pocket.exists' ($pockets.Count -ge 1) "pockets=$($pockets.Count)"
$p0 = $pockets | Select-Object -First 1
Check 'pocket.fields' ([bool]$p0.pocketNo -and [bool]$p0.method) `
  "pocketNo=$($p0.pocketNo) method=$($p0.method) score=$($p0.score) druggability=$($p0.druggability)"

# 独立端点：详情页点开结构时也能单取口袋
$pk = Invoke-Json 'GET' "/api/design/structures/$($withPdb.id)/pockets" $null $token
Check 'pocket.endpoint' ($pk.Status -eq 200 -and @($pk.Body.data).Count -ge 1) "pockets=$(@($pk.Body.data).Count)"

# --- 可立项性：TP53 无结构 → 页面应显示「不可立项」 ---
$tp53 = $list | Where-Object { $_.name -eq 'TP53' } | Select-Object -First 1
$tp53Structs = @($tp53.structures)
Check 'readiness.tp53' ($tp53Structs.Count -eq 0) "TP53 structures=$($tp53Structs.Count) status=$($tp53.status)"

# --- 详情端点 ---
$detail = Invoke-Json 'GET' "/api/design/targets/$($egfr.id)" $null $token
Check 'target.detail' ($detail.Status -eq 200 -and @($detail.Body.data.structures).Count -ge 1) `
  "structures=$(@($detail.Body.data.structures).Count)"

# --- 筛选参数（页面下拉用） ---
$byType = Invoke-Json 'GET' ('/api/design/targets?targetType=' + [uri]::EscapeDataString('受体酪氨酸激酶') + '&page=1&pageSize=50') $null $token
Check 'target.filter.type' ($byType.Status -eq 200 -and @($byType.Body.data.list).Count -ge 1) "count=$(@($byType.Body.data.list).Count)"

Write-Output ''
Write-Output ("RESULT  pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
