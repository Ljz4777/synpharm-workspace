# 需求基线「可编辑化」端到端验证：靶点绑定 / 约束集增删与激活 / 风险预设
# 前置：10 个容器已起，网关 8080 可达，靶点库有 EGFR（fixture-target.sql）
#
# 这份脚本验证的是「用户在向导里点得动」这条链路 —— 每一步都用页面会调用的
# 真实接口，而不是直接改库，否则测不出前端接线是否对。
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
  $args = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $obj) {
    $args['ContentType'] = 'application/json; charset=utf-8'
    $args['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10 -Compress))
  }
  try {
    $r = Invoke-WebRequest @args
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

function Block-State($preview, [string]$code) {
  $b = @($preview.blocks) | Where-Object { $_.code -eq $code }
  if ($b) { return $b[0].state } else { return 'ABSENT' }
}

function Err-Code($resp) {
  if ($null -eq $resp.Body) { return '' }
  return [string]$resp.Body.code
}

# ---------------------------------------------------------------- 0) 登录
$login = Invoke-Json 'POST' '/api/auth/login' @{ loginType = 'guest' } $null
$token = $login.Body.data.accessToken
Check 'auth.guest' ($login.Status -eq 200 -and $token) "userId=$($login.Body.data.user.id)"
if (-not $token) { exit 2 }

# ---------------------------------------------------------------- 1) 靶点库
$tl = Invoke-Json 'GET' '/api/design/targets?page=1&pageSize=20' $null $token
$target = @($tl.Body.data.list) | Where-Object { $_.name -eq 'EGFR' } | Select-Object -First 1
if (-not $target) {
  Write-Output 'PRECONDITION FAIL  靶点库缺少 EGFR，请先执行 fixture-target.sql'
  exit 2
}
$targetId = $target.id
# 第二个靶点用于验证「多靶点绑定 / 解绑」
$target2 = @($tl.Body.data.list) | Where-Object { $_.name -ne 'EGFR' } | Select-Object -First 1
Check 'target.list' ($null -ne $targetId) "egfr=$targetId second=$(if ($target2) { $target2.id } else { 'none' })"

# ---------------------------------------------------------------- 2) 立项（故意不带约束：验证向导能补）
$stamp = Get-Date -Format 'MMddHHmmss'
$body = @{
  name                = "E2E 基线编辑 $stamp"
  modalityId          = 1
  moaCode             = 'INHIBITOR'
  minCandidates       = 10
  targets             = @(@{ targetId = $targetId; role = 'WILD_TYPE'; isPrimary = $true; sortNo = 0 })
}
$created = Invoke-Json 'POST' '/api/design/projects' $body $token
$pid2 = $created.Body.data.id
Check 'project.create' (($created.Status -eq 200 -or $created.Status -eq 201) -and $pid2) "projectId=$pid2 status=$($created.Status)"

$base = "/api/design/projects/$pid2"

# 关键前提：新项目没有绑定约束集
$c0 = Invoke-Json 'GET' "$base/constraints" $null $token
Check 'constraints.empty' ($c0.Status -eq 200 -and $null -eq $c0.Body.data) "data=$(if ($null -eq $c0.Body.data) { 'null' } else { 'set' })"

$p0 = (Invoke-Json 'POST' "$base/baseline:preview" $null $token).Body.data
Check 'preview.hard-missing' ((Block-State $p0 'HARD_RULES') -eq 'MISSING') "state=$(Block-State $p0 'HARD_RULES')"
Check 'preview.metric-missing' ((Block-State $p0 'METRICS') -eq 'MISSING') "state=$(Block-State $p0 'METRICS')"

# ---------------------------------------------------------------- 3) 靶点：绑定第二个 / 解绑 / 主靶点唯一性
if ($target2) {
  $bind2 = Invoke-Json 'POST' "$base/targets" @{ targetId = $target2.id; role = 'OFF_TARGET'; isPrimary = $false; sortNo = 1 } $token
  Check 'target.bind.second' ($bind2.Status -eq 200 -or $bind2.Status -eq 201) "status=$($bind2.Status) targetId=$($target2.id)"

  $dup = Invoke-Json 'POST' "$base/targets" @{ targetId = $target2.id; role = 'OFF_TARGET'; isPrimary = $false } $token
  Check 'target.bind.duplicated-409' ($dup.Status -eq 409) "status=$($dup.Status) code=$(Err-Code $dup)"

  $secPrimary = Invoke-Json 'POST' "$base/targets" @{ targetId = $targetId; role = 'WILD_TYPE'; isPrimary = $true } $token
  # 重复绑定同一条会先命中 40944 或 40943，两者都算「被挡住了」
  Check 'target.primary-unique' ($secPrimary.Status -eq 409) "status=$($secPrimary.Status) code=$(Err-Code $secPrimary)"

  $unbind = Invoke-Json 'DELETE' "$base/targets/$($target2.id)" $null $token
  Check 'target.unbind' ($unbind.Status -eq 200) "status=$($unbind.Status)"

  $proj = (Invoke-Json 'GET' $base $null $token).Body.data
  Check 'target.count-back-to-1' (@($proj.targets).Count -eq 1) "targets=$(@($proj.targets).Count)"

  # 回归：解绑后重新绑定同一个靶点。此前走逻辑删除，会撞 uk_project_target 报 500
  $rebind = Invoke-Json 'POST' "$base/targets" @{ targetId = $target2.id; role = 'OFF_TARGET'; isPrimary = $false; sortNo = 1 } $token
  Check 'target.rebind-after-unbind' (($rebind.Status -eq 200) -or ($rebind.Status -eq 201)) "status=$($rebind.Status) code=$(Err-Code $rebind)"

  $proj2 = (Invoke-Json 'GET' $base $null $token).Body.data
  Check 'target.count-back-to-2' (@($proj2.targets).Count -eq 2) "targets=$(@($proj2.targets).Count)"

  # 解绑主靶点后再设主靶点：uk_primary_target 也不该被残留行占住
  $unbindPrimary = Invoke-Json 'DELETE' "$base/targets/$targetId" $null $token
  $makePrimary = Invoke-Json 'POST' "$base/targets" @{ targetId = $targetId; role = 'WILD_TYPE'; isPrimary = $true; sortNo = 0 } $token
  Check 'target.rebind-primary-after-unbind' (($makePrimary.Status -eq 200) -or ($makePrimary.Status -eq 201)) "unbind=$($unbindPrimary.Status) bind=$($makePrimary.Status) code=$(Err-Code $makePrimary)"
}

# ---------------------------------------------------------------- 4) 约束：缺 humanReadable 必须被挡
$badRule = @{
  name        = "坏约束集 $stamp"
  description = '缺 humanReadable，应被校验器拒绝'
  rules       = @(@{
      ruleType      = 'NUMERIC_RANGE'
      severity      = 'HARD'
      params        = @{ field = 'MolWt'; min = 150; max = 600 }
      humanReadable = ''
    })
}
$bad = Invoke-Json 'POST' '/api/design/constraints' $badRule $token
Check 'constraint.reject-blank-human-readable' ($bad.Status -eq 400) "status=$($bad.Status) code=$(Err-Code $bad)"

# min > max 也必须被挡
$badRange = @{
  name  = "坏区间 $stamp"
  rules = @(@{
      ruleType      = 'NUMERIC_RANGE'
      severity      = 'HARD'
      params        = @{ field = 'MolWt'; min = 600; max = 150 }
      humanReadable = '分子量 600~150'
    })
}
$bad2 = Invoke-Json 'POST' '/api/design/constraints' $badRange $token
Check 'constraint.reject-min-gt-max' ($bad2.Status -eq 400) "status=$($bad2.Status) code=$(Err-Code $bad2)"

# ---------------------------------------------------------------- 5) 约束：按预设建集 + 激活
# 规则内容与 utils/baselinePresets.ts 的 HARD_RULE_PRESETS 保持一致
$rules = @(
  @{ ruleType = 'NUMERIC_RANGE'; severity = 'HARD'; params = @{ field = 'MolWt'; min = 150; max = 600 }; humanReadable = '分子量 150~600 Da'; sortNo = 0; enabled = $true },
  @{ ruleType = 'NUMERIC_RANGE'; severity = 'HARD'; params = @{ field = 'LogP'; min = -0.4; max = 5 }; humanReadable = 'LogP 在 −0.4~5.0 之间'; sortNo = 1; enabled = $true },
  @{ ruleType = 'NUMERIC_RANGE'; severity = 'HARD'; params = @{ field = 'TPSA'; max = 140 }; humanReadable = '极性表面积 TPSA ≤ 140 Ų'; sortNo = 2; enabled = $true },
  @{ ruleType = 'NUMERIC_RANGE'; severity = 'SOFT'; weight = 1; params = @{ field = 'QED'; min = 0.5 }; humanReadable = '类药性 QED ≥ 0.5'; sortNo = 3; enabled = $true }
)
$setBody = @{ name = "约束集 v1 $stamp"; description = '由需求基面向导生成，共 4 条规则'; rules = $rules; version = 1 }
$set = Invoke-Json 'POST' '/api/design/constraints' $setBody $token
$setId = $set.Body.data.id
Check 'constraint.create' (($set.Status -eq 200 -or $set.Status -eq 201) -and $setId) "setId=$setId status=$($set.Body.data.status) rules=$(@($set.Body.data.rules).Count)"
Check 'constraint.draft-status' ($set.Body.data.status -eq 'DRAFT') "status=$($set.Body.data.status)"

$bind = Invoke-Json 'POST' "$base/constraints" @{ constraintSetId = $setId; activate = $true } $token
Check 'constraint.bind-activate' (($bind.Status -eq 200) -and $bind.Body.data.status -eq 'ACTIVE') "status=$($bind.Status) setStatus=$($bind.Body.data.status)"

$c1 = (Invoke-Json 'GET' "$base/constraints" $null $token).Body.data
Check 'constraint.read-back' ($c1.id -eq $setId -and @($c1.rules).Count -eq 4) "setId=$($c1.id) rules=$(@($c1.rules).Count) setNo=$($c1.setNo)"

$p1 = (Invoke-Json 'POST' "$base/baseline:preview" $null $token).Body.data
Check 'preview.hard-ok' ((Block-State $p1 'HARD_RULES') -eq 'OK') "state=$(Block-State $p1 'HARD_RULES')"
Check 'preview.soft-ok' ((Block-State $p1 'SOFT_RULES') -eq 'OK') "state=$(Block-State $p1 'SOFT_RULES')"

# 第二次保存 = 新建一版（模拟用户改规则后再次点保存）
$set2Body = @{ name = "约束集 v2 $stamp"; description = '追加一条可旋转键约束'; version = 2; rules = @($rules + @{
        ruleType = 'NUMERIC_RANGE'; severity = 'HARD'; params = @{ field = 'RotatableBonds'; max = 10 }; humanReadable = '可旋转键数 ≤ 10'; sortNo = 4; enabled = $true
      }) }
$set2 = Invoke-Json 'POST' '/api/design/constraints' $set2Body $token
$set2Id = $set2.Body.data.id
$bind2r = Invoke-Json 'POST' "$base/constraints" @{ constraintSetId = $set2Id; activate = $true } $token
Check 'constraint.new-version' (($set2Id -ne $setId) -and $bind2r.Body.data.status -eq 'ACTIVE') "v2SetId=$set2Id rules=$(@($bind2r.Body.data.rules).Count)"

$old = Invoke-Json 'GET' "/api/design/constraints/$setId" $null $token
Check 'constraint.old-version-kept' (@($old.Body.data.rules).Count -eq 4) "oldRules=$(@($old.Body.data.rules).Count) oldStatus=$($old.Body.data.status)"

# ---------------------------------------------------------------- 6) 指标：按分子形式补齐
$init = Invoke-Json 'POST' "$base/metrics:init-from-modality" $null $token
Check 'metric.init' ($init.Status -eq 200 -and @($init.Body.data).Count -gt 0) "added=$(@($init.Body.data).Count)"

# ---------------------------------------------------------------- 7) 风险点：预设整批写入
$riskPresets = @(
  @{ riskCode = 'HEPATOTOXICITY'; dimension = 'SAFETY'; description = '肝毒性风险：关注结构警示与体外肝细胞活性'; severity = 'HIGH'; mustVerify = $false; source = 'MANUAL' },
  @{ riskCode = 'PLASMA_STABILITY'; dimension = 'STABILITY'; description = '血浆/血清中被快速降解的风险'; severity = 'MEDIUM'; mustVerify = $true; source = 'MANUAL' },
  @{ riskCode = 'AGGREGATION_RISK'; dimension = 'DEVELOPABILITY'; description = '高浓度下易聚集，影响制剂与给药'; severity = 'MEDIUM'; mustVerify = $false; source = 'MANUAL' }
)
foreach ($r in $riskPresets) { $null = Invoke-Json 'POST' "$base/risks" $r $token }

$riskList = (Invoke-Json 'GET' "$base/risks" $null $token).Body.data
Check 'risk.presets-written' (@($riskList).Count -eq 3) "count=$(@($riskList).Count)"
$plasma = @($riskList) | Where-Object { $_.riskCode -eq 'PLASMA_STABILITY' } | Select-Object -First 1
Check 'risk.preset-fields' ($plasma.dimension -eq 'STABILITY' -and $plasma.mustVerify -eq $true -and $plasma.severity -eq 'MEDIUM') "dimension=$($plasma.dimension) severity=$($plasma.severity) mustVerify=$($plasma.mustVerify)"

$p2 = (Invoke-Json 'POST' "$base/baseline:preview" $null $token).Body.data
Check 'preview.risks-ok' ((Block-State $p2 'RISKS') -eq 'OK') "state=$(Block-State $p2 'RISKS')"
Check 'preview.metrics-ok' ((Block-State $p2 'METRICS') -eq 'OK') "state=$(Block-State $p2 'METRICS')"

# ---------------------------------------------------------------- 8) 冻结
$freeze = Invoke-Json 'POST' "$base/baseline:freeze" @{ expectedContentHash = $p2.contentHash } $token
Check 'baseline.freeze' ($freeze.Status -eq 200) "status=$($freeze.Status) snap=$($freeze.Body.data.snapshotNo) code=$(Err-Code $freeze)"

$snap = $freeze.Body.data
$hardCount = 0
if ($snap -and $snap.hardRules -and $snap.hardRules.items) { $hardCount = @($snap.hardRules.items).Count }
Check 'baseline.snapshot-hard-rules' ($snap.completeness.HARD_RULES -eq 'OK' -and $hardCount -eq 4) "completeness=$($snap.completeness.HARD_RULES) rules=$hardCount"
$softCount = 0
if ($snap -and $snap.softRules -and $snap.softRules.items) { $softCount = @($snap.softRules.items).Count }
Check 'baseline.snapshot-soft-rules' ($snap.completeness.SOFT_RULES -eq 'OK' -and $softCount -ge 1) "completeness=$($snap.completeness.SOFT_RULES) softRules=$softCount"

# 快照里的规则必须带 humanReadable（红线 R2：下游只看得到人能读懂的那句话）
$allReadable = $true
foreach ($item in @($snap.hardRules.items)) {
  if ([string]::IsNullOrWhiteSpace([string]$item.humanReadable)) { $allReadable = $false }
}
Check 'baseline.snapshot-rules-readable' $allReadable "hardRules=$hardCount"

# 冻结时回传过期的 contentHash 必须被挡住（预检后又有人改过项目的那条路）
$stale = Invoke-Json 'POST' "$base/baseline:freeze" @{ expectedContentHash = 'deadbeefdeadbeef' } $token
Check 'baseline.freeze-stale-409' ($stale.Status -eq 409) "status=$($stale.Status) code=$(Err-Code $stale)"

Write-Output ''
Write-Output ("RESULT pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
