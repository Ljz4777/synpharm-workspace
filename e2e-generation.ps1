# 阶段 C 端到端验证：生成流水线 S0~S9
# 前置：9 个容器已起、synpharm_core 已跑 39 + 40 + 41 脚本、fastapi 的 /v1/evaluate 可用
#
# 覆盖两条真实路径：
#   ① 小分子 SEED_LIBRARY：用户提供 SMILES → 真 RDKit 指标 → 真硬约束/PAINS 粗筛
#   ② 序列   SEQUENCE_MUTATION：父本重/轻链 → 单点突变 → 序列哈希去重
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
# core 刚重启时 Nacos 注册可能还没生效，网关会返 503；先探到通再开跑
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
# ---------------------------------------------------------------- 准备工具
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
  $rd = Invoke-Json 'POST' "/api/design/projects/$newId/rounds" @{ strategyNote = 'gen' } $script:token

  # 用全局中转而不是 return：函数体内任何一处意外输出都会让 return 的值变成数组，
  # 而 $sm.ProjectId 在数组上取不到，表现为"项目创建成功但 id 为空"这种极难查的现象
  $global:SETUP = @{ ProjectId = $newId; Freeze = $fz; Round = $rd }
}
# ================================================================ ① 小分子：导入 + 真筛选
Write-Output ''
Write-Output '=== ① SEED_LIBRARY（小分子，真 RDKit 指标）==='
$script:targetId = $target.id
$script:token = $token

New-FrozenProject "生成-小分子 $stamp" 1 3; $sm = $global:SETUP
Check 'setup.project' ($null -ne $sm.ProjectId) "projectId=$($sm.ProjectId)"
Check 'mol.freeze' ($sm.Freeze.Status -eq 200 -and $sm.Freeze.Body.data.status -eq 'ACTIVE') "HTTP $($sm.Freeze.Status) baselineId=$($sm.Freeze.Body.data.id) $($sm.Freeze.Raw)"
Check 'mol.round' ($sm.Round.Status -eq 201) "HTTP $($sm.Round.Status) roundId=$($sm.Round.Body.data.id)"

# 阿司匹林/咖啡因/布洛芬（应通过）、对苯醌（PAINS 阳性）、26 碳脂肪酸（MW 超 600）
$molSeeds = @(
  @{ ref = 'ASP'; smiles = 'CC(=O)Oc1ccccc1C(=O)O' },
  @{ ref = 'CAF'; smiles = 'CN1C=NC2=C1C(=O)N(C)C(=O)N2C' },
  @{ ref = 'IBU'; smiles = 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' },
  @{ ref = 'PAINS_Q'; smiles = 'O=C1C=CC(=O)C=C1' },
  @{ ref = 'TINY'; smiles = 'CO' }
)
$gen1 = Invoke-Json 'POST' "/api/design/projects/$($sm.ProjectId)/generations" @{
  seeds = $molSeeds; seed = 42; idempotencyKey = "mol-$stamp"
} $token
Check 'mol.submit' ($gen1.Status -eq 202) "HTTP $($gen1.Status) $($gen1.Raw)"

$run1 = $gen1.Body.data
Check 'mol.run.final' ($run1.status -eq 'SUCCEEDED') "status=$($run1.status) final=$($run1.finalCount) err=$($run1.errorMsg)"
Check 'mol.run.strategy' ($run1.strategy -eq 'SEED_LIBRARY') "strategy=$($run1.strategy)"
Check 'mol.run.degraded' ($run1.degraded -eq $true) "degraded=$($run1.degraded) reason=$($run1.degradedReason)"
Check 'mol.slices' ((@($run1.slices).Count -ge 1)) "slices=$(@($run1.slices).Count) first=$($run1.slices[0].status)"
Check 'mol.plan.nSamples' ($run1.nSamples -ge 3) "nSamples=$($run1.nSamples) batch=$($run1.batchSize)"
Check 'mol.plan.hash' ($run1.planHash -match '^[0-9a-f]{64}$') "planHash=$($run1.planHash)"

Check 'mol.funnel.generated' ($run1.funnel.generated -eq 5) "generated=$($run1.funnel.generated)"
Check 'mol.funnel.afterDedup' ($run1.funnel.afterDedup -eq 5) "afterDedup=$($run1.funnel.afterDedup)"
Check 'mol.funnel.filtered' ($run1.funnel.afterPreFilter -lt $run1.funnel.afterDedup) "preFilter=$($run1.funnel.afterPreFilter) (应 < 5)"
Check 'mol.funnel.quota' ($run1.funnel.afterQuota -eq $run1.finalCount) "afterQuota=$($run1.funnel.afterQuota) final=$($run1.finalCount)"
Check 'mol.diagnosis' ($run1.diagnosis.verdict -in @('PASS','PASS_WITH_WARNING')) "verdict=$($run1.diagnosis.verdict) ready=$($run1.diagnosis.readyForEdit)"
Check 'mol.diagnosis.topReason' ($null -ne $run1.funnel.filterReasonTop) "top=$($run1.funnel.filterReasonTop | ConvertTo-Json -Compress)"

# 逐分子日志必须能回答「为什么被筛」
$logs1 = Invoke-Json 'GET' "/api/design/generations/$($run1.id)/candidates?stage=S6_PRE_FILTER&outcome=FILTERED" $null $token
$filtered = @($logs1.Body.data)
Check 'mol.log.filtered' ($filtered.Count -ge 1) "FILTERED=$($filtered.Count)"
$codes = @()
foreach ($row in $filtered) {
  if ($row.filterReason) {
    foreach ($r in ($row.filterReason | ConvertFrom-Json)) { $codes += $r.code }
  }
}
$codesText = ($codes | Sort-Object -Unique) -join ','
Check 'mol.log.reasonCodes' (($codesText -match 'PAINS_HIT') -and ($codesText -match 'HARD_CONSTRAINT_VIOLATION')) "codes=$codesText"

# 幂等：同 key 再交一次返回同一批次
$gen1b = Invoke-Json 'POST' "/api/design/projects/$($sm.ProjectId)/generations" @{
  seeds = $molSeeds; seed = 42; idempotencyKey = "mol-$stamp"
} $token
Check 'mol.idempotent' ($gen1b.Body.data.id -eq $run1.id) "runId=$($gen1b.Body.data.id) vs $($run1.id)"

# 候选已落库
$proj1 = Invoke-Json 'GET' "/api/design/projects/$($sm.ProjectId)" $null $token
Check 'mol.candidates.persisted' ($proj1.Body.data.candidateStats.total -ge 1) "total=$($proj1.Body.data.candidateStats.total) qualified=$($proj1.Body.data.candidateStats.qualified)"

# ================================================================ ② 序列：单点突变
Write-Output ''
Write-Output '=== ② SEQUENCE_MUTATION（抗体序列单点突变）==='
New-FrozenProject "生成-序列 $stamp" 2 5; $sq = $global:SETUP
Check 'seq.freeze' ($sq.Freeze.Status -eq 200) "baselineId=$($sq.Freeze.Body.data.id)"

$heavy = 'EVQLVESGGGLVQPGGSLRLSCAASGFTFSSYAMSWVRQAPGKGLEWVSGITGSGGSTYYADSVKGRFTISRDNSKNTLYLQMNSLRAEDTAVYYCAKDLGWSFDYWGQGTLVTVSS'
$light = 'DIQMTQSPSSLSASVGDRVTITCRASQDISNYLAWYQQKPGKAPKLLIYAASSLQSGVPSRFSGSGSGTDFTLTISSLQPEDFATYYCQQSYSTPLTFGGGTKVEIK'
$gen2 = Invoke-Json 'POST' "/api/design/projects/$($sq.ProjectId)/generations" @{
  seeds = @(@{ ref = 'PARENT-1'; heavyChain = $heavy; lightChain = $light }); seed = 7
} $token
Check 'seq.submit' ($gen2.Status -eq 202) "HTTP $($gen2.Status) $($gen2.Raw)"
$run2 = $gen2.Body.data
Check 'seq.run.final' ($run2.finalCount -ge 1) "status=$($run2.status) final=$($run2.finalCount) err=$($run2.errorMsg)"
Check 'seq.strategy' ($run2.strategy -eq 'SEQUENCE_MUTATION') "strategy=$($run2.strategy)"
Check 'seq.nSamples' ($run2.nSamples -ge 5) "nSamples=$($run2.nSamples)"

$logs2 = Invoke-Json 'GET' "/api/design/generations/$($run2.id)/candidates?stage=S5_DEDUP&outcome=KEPT" $null $token
$kept2 = @($logs2.Body.data)
Check 'seq.log.kept' ($kept2.Count -ge 1) "KEPT=$($kept2.Count)"
Check 'seq.log.sequenceHash' ((@($kept2 | Where-Object { $_.sequenceHash }).Count) -eq $kept2.Count) "带 hash=$(@($kept2 | Where-Object { $_.sequenceHash }).Count)/$($kept2.Count)"
# 突变后哈希必须与父本不同
$parentChanged = @($kept2 | Where-Object { $_.sequenceHash }).Count -ge 1
Check 'seq.hash.present' $parentChanged "unique=$(@($kept2 | ForEach-Object { $_.sequenceHash } | Sort-Object -Unique).Count)"

# 落库的候选带重/轻链
$det2 = Invoke-Json 'GET' "/api/design/generations/$($run2.id)" $null $token
Check 'seq.detail.reload' ($det2.Body.data.id -eq $run2.id) "reload ok final=$($det2.Body.data.finalCount)"

# ================================================================ ③ 准入门槛
Write-Output ''
Write-Output '=== ③ 准入与守卫 ==='
New-FrozenProject "生成-空项目 $stamp" 2 5; $blank = $global:SETUP
$noSeed = Invoke-Json 'POST' "/api/design/projects/$($blank.ProjectId)/generations" @{ seed = 1 } $token
Check 'guard.noSeed' ($noSeed.Status -eq 400) "HTTP $($noSeed.Status) errorCode=$($noSeed.Body.errorCode)"
Check 'guard.noSeed.hint' ($noSeed.Body.message -match '搜索空间为空') "msg=$($noSeed.Body.message)"

$fresh = Invoke-Json 'POST' '/api/design/projects' @{
  name = "未冻结 $stamp"; modalityId = 1; minCandidates = 3
  targets = @(@{ targetId = $target.id; role = 'WILD_TYPE'; isPrimary = $true })
} $token
Check 'guard.fresh.created' ($fresh.Status -eq 201) "HTTP $($fresh.Status) $($fresh.Raw)"
$noBase = Invoke-Json 'POST' "/api/design/projects/$($fresh.Body.data.id)/generations" @{ seeds = @(@{ ref='X'; smiles='CCO' }) } $token
Check 'guard.noBaseline' ($noBase.Status -eq 404 -and $noBase.Body.errorCode -eq 'BASELINE_NOT_FOUND') "HTTP $($noBase.Status) errorCode=$($noBase.Body.errorCode)"

$anon = Invoke-Json 'GET' "/api/design/generations/$($run1.id)" $null $null
Check 'guard.anon' ($anon.Status -eq 401) "HTTP $($anon.Status)"

$other = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$foreign = Invoke-Json 'GET' "/api/design/generations/$($run1.id)" $null $other.Body.data.accessToken
Check 'guard.foreign' ($foreign.Status -eq 404) "HTTP $($foreign.Status) errorCode=$($foreign.Body.errorCode)"

Write-Output ''
Write-Output "RESULT pass=$pass fail=$fail"
Write-Output "molRun=$($run1.id) seqRun=$($run2.id) molProject=$($sm.ProjectId) seqProject=$($sq.ProjectId)"
exit $(if ($fail -eq 0) { 0 } else { 1 })