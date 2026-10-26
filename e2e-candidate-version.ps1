# 候选版本写回（二维画布 → 同一分子的下一版）端到端验证
#
# 覆盖：
#   ① 合法改动 → 版本树新增一版（versionNo+1、parent 指向父版本、originType=EDITED、needsReEval=1）
#   ② 改动说明落进 molecule_edit_log（决策证据血缘读的就是它）；树节点的 note 也带这句说明
#   ③ 结构没变（InChIKey 相同）→ 400 MOLECULE_EDIT_NO_CHANGE，不产生新版本
#   ④ 说明为空 → 400 EDIT_REASON_REQUIRED
#   ⑤ 结构无法解析 → 400 MOLECULE_EDIT_INVALID
#   ⑥ 未重新评估前诊断 → 409 STALE_OBJECT（带着旧分数进决策等于拿上一个分子下单）
#   ⑦ 重新冻结后 needsReEval 归零、诊断可用
#   ⑧ 放弃分支不能再派生新版本 → 409 BUSINESS_CONFLICT
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
  name            = "Version-$stamp"
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
$null = Invoke-Json 'POST' "/api/design/projects/$projId/rounds" @{ strategyNote = 'version' } $token

# 父版本：阿司匹林（画布建出来的）
$parent = Invoke-Json 'POST' "/api/design/projects/$projId/candidates" @{
  smiles = 'CC(=O)Oc1ccccc1C(=O)O'; moleculeName = "版本树-$stamp"
} $token
$parentId = $parent.Body.data.candidateId
$parentKey = $parent.Body.data.inchikey
Check 'setup.parent' ($parentId -gt 0 -and $parentKey -eq 'BSYNRYMUTXBXSQ-UHFFFAOYSA-N') `
  "parent=#$parentId inchikey=$parentKey"
$null = Invoke-Json 'POST' "/api/design/candidates/$parentId/evaluation:freeze" @{} $token

# ---------------------------------------------------------------- ④ 说明必填
$noReason = Invoke-Json 'POST' "/api/design/candidates/$parentId/versions" @{ smiles = 'CC(=O)Oc1ccc(cc1)C(=O)O' } $token
Check 'version.reasonRequired' ($noReason.Status -eq 400) "status=$($noReason.Status) code=$($noReason.Body.errorCode)"

# ---------------------------------------------------------------- ⑤ 结构非法
$bad = Invoke-Json 'POST' "/api/design/candidates/$parentId/versions" @{
  smiles = '这不是结构'; describeText = '随便改改'
} $token
Check 'version.invalidSmiles' ($bad.Status -eq 400 -and $bad.Body.errorCode -eq 'MOLECULE_EDIT_INVALID') `
  "status=$($bad.Status) code=$($bad.Body.errorCode)"

# ---------------------------------------------------------------- ③ 结构没变
$same = Invoke-Json 'POST' "/api/design/candidates/$parentId/versions" @{
  smiles = 'OC(=O)c1ccccc1OC(C)=O'; describeText = '原样重存'
} $token
Check 'version.noChange' ($same.Status -eq 400 -and $same.Body.errorCode -eq 'MOLECULE_EDIT_NO_CHANGE') `
  "status=$($same.Status) code=$($same.Body.errorCode)"

# ---------------------------------------------------------------- ① 合法改动
$child = Invoke-Json 'POST' "/api/design/candidates/$parentId/versions" @{
  smiles = 'CC(=O)Oc1ccc(cc1)C(=O)O'
  describeText = '把乙酰氧基从邻位挪到对位（测试写回）'
  targetRegion = 'C4'
} $token
$childData = $child.Body.data
Check 'version.create' (($child.Status -eq 200 -or $child.Status -eq 201) -and $childData.candidateId) `
  "status=$($child.Status) child=#$($childData.candidateId) v$($childData.versionNo) code=$($child.Body.errorCode)"
$childId = $childData.candidateId
Check 'version.parentLinked' ($childData.parentCandidateId -eq $parentId) "parentId=$($childData.parentCandidateId)"
Check 'version.versionNo' ($childData.versionNo -eq 2 -and $childData.editRound -eq 1) `
  "versionNo=$($childData.versionNo) editRound=$($childData.editRound)"
Check 'version.originType' ($childData.originType -eq 'EDITED') "originType=$($childData.originType)"
Check 'version.needsReEval' ($childData.needsReEval -eq $true) "needsReEval=$($childData.needsReEval)"
Check 'version.structureChanged' ($childData.inchikey -ne $parentKey) `
  "parent=$parentKey child=$($childData.inchikey)"
Check 'version.engineComputed' ($childData.formula -eq 'C9H8O4' -and $null -ne $childData.mw -and $childData.metricCount -ge 10) `
  "formula=$($childData.formula) mw=$($childData.mw) metricCount=$($childData.metricCount)"
Check 'version.editLogWritten' ($null -ne $childData.editLogId -and $childData.editLogId -gt 0) `
  "editLogId=$($childData.editLogId)"

# ---------------------------------------------------------------- ② 版本树与说明
$tree = Invoke-Json 'GET' "/api/design/candidates/$childId/tree" $null $token
$nodes = @($tree.Body.data.nodes)
$childNode = @($nodes | Where-Object { $_.id -eq $childId })[0]
$parentNode = @($nodes | Where-Object { $_.id -eq $parentId })[0]
Check 'tree.rootIsParent' ($tree.Body.data.rootId -eq $parentId) "root=$($tree.Body.data.rootId)"
Check 'tree.bothNodes' ($nodes.Count -eq 2 -and $null -ne $childNode -and $null -ne $parentNode) "nodes=$($nodes.Count)"
Check 'tree.childLinked' ($childNode.parentId -eq $parentId -and $childNode.depth -eq 1) `
  "parentId=$($childNode.parentId) depth=$($childNode.depth) versionNo=$($childNode.versionNo)"
Check 'tree.describeVisible' ($childNode.note -like '*把乙酰氧基从邻位挪到对位*') "note=$($childNode.note)"

# ---------------------------------------------------------------- ⑥ 未重新评估
$stale = Invoke-Json 'POST' "/api/design/candidates/$childId/gaps:diagnose" @{} $token
Check 'version.staleBeforeFreeze' ($stale.Status -eq 409 -and $stale.Body.errorCode -eq 'STALE_OBJECT') `
  "status=$($stale.Status) code=$($stale.Body.errorCode)"

# ---------------------------------------------------------------- ⑦ 重新冻结
$freeze = Invoke-Json 'POST' "/api/design/candidates/$childId/evaluation:freeze" @{} $token
Check 'version.freeze' ($freeze.Status -eq 200 -and $freeze.Body.data.freezeNo) `
  "freezeNo=$($freeze.Body.data.freezeNo) coverage=$($freeze.Body.data.weightCoverage)"
$diag = Invoke-Json 'POST' "/api/design/candidates/$childId/gaps:diagnose" @{} $token
Check 'version.diagnoseAfterFreeze' ($diag.Status -eq 200) "status=$($diag.Status) gaps=$(@($diag.Body.data.gaps).Count)"

# ---------------------------------------------------------------- ⑧ 放弃分支
$null = Invoke-Json 'POST' "/api/design/candidates/$childId`:abandon" @{} $token
$abandoned = Invoke-Json 'POST' "/api/design/candidates/$childId/versions" @{
  smiles = 'CC(=O)Oc1ccccc1C(O)=O'; describeText = '从已放弃的分支再派生'
} $token
Check 'version.abandonedBlocked' ($abandoned.Status -eq 409 -and $abandoned.Body.errorCode -eq 'BUSINESS_CONFLICT') `
  "status=$($abandoned.Status) code=$($abandoned.Body.errorCode)"

Write-Output ''
Write-Output ("RESULT  project={0} parent=#{1} child=#{2} editLog={3} pass={4} fail={5}" -f $projId, $parentId, $childId, $childData.editLogId, $pass, $fail)
if ($fail -gt 0) { exit 1 }
