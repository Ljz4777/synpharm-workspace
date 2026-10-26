<template>
  <div class="pj">
  <Sidebar />
  <main class="pj__main">
    <div class="page">
    <PageHeader title="设计项目" subtitle="需求分析 → 生成 → 评估与编辑：每个阶段都在这里进入">
      <template #actions>
        <button type="button" class="btn" :disabled="loading" @click="loadAll">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button type="button" class="btn" :disabled="seeding" @click="runDemo">
          {{ seeding ? seedStep : '一键体验' }}
        </button>
        <button type="button" class="btn btn--primary" @click="toggleCreate">
          {{ showCreate ? '取消' : '+ 新建项目' }}
        </button>
      </template>
    </PageHeader>

    <p v-if="error" class="banner banner--danger">{{ error }}</p>
    <p v-if="notice" class="banner banner--ok">{{ notice }}</p>

    <!-- 新建项目：建完直接进基线页补齐八块，不用再回列表找入口 -->
    <section v-if="showCreate" class="create">
      <header class="create__head">
        <h3 class="create__title">新建项目</h3>
        <p class="create__hint">
          这里只填「能立项」的最小信息。建好后会直接进需求分析页补齐基线八块 ——
          八块不齐不允许冻结，而冻结的基线才是生成任务的唯一输入。
        </p>
      </header>

      <div class="create__grid">
        <label class="field field--wide">
          <span class="field__label">项目名称 *</span>
          <input v-model.trim="form.name" type="text" maxlength="200" class="field__control"
                 placeholder="例：EGFR 小分子亲和力优化" />
        </label>
        <label class="field">
          <span class="field__label">分子形式 *</span>
          <select v-model.number="form.modalityId" class="field__control">
            <option v-for="m in modalityOptions" :key="m.id" :value="m.id">{{ m.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">主靶点 *</span>
          <select v-model.number="form.targetId" class="field__control">
            <option v-for="t in targetOptions" :key="t.id" :value="t.id">{{ t.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">最少候选数 *</span>
          <input v-model.number="form.minCandidates" type="number" min="1" class="field__control" />
        </label>
        <label class="field">
          <span class="field__label">作用机制</span>
          <select v-model="form.moaCode" class="field__control">
            <option value="">— 暂不指定 —</option>
            <option v-for="o in moaOptions" :key="o.code" :value="o.code">{{ o.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">GPU 配额（小时）</span>
          <input v-model.number="form.quotaGpuHours" type="number" min="0" class="field__control" />
        </label>
        <label class="field">
          <span class="field__label">湿实验名额</span>
          <input v-model.number="form.quotaWetLab" type="number" min="0" class="field__control" />
        </label>
      </div>

      <label class="create__check">
        <input v-model="form.withHardRule" type="checkbox" />
        <span>
          预置一条硬约束「分子量 150~600 Da」
          <em>（基线块4 至少要有一条硬约束才能冻结；建好后可在需求分析页改）</em>
        </span>
      </label>

      <div class="create__actions">
        <button type="button" class="btn btn--primary" :disabled="creating || !form.name || !form.modalityId || !form.targetId"
                @click="createProject">
          {{ creating ? '创建中…' : '创建并去补齐基线 →' }}
        </button>
        <span class="create__tip">分子形式决定后续能算哪些指标、能做哪些编辑动作</span>
      </div>
    </section>

    <section v-if="!projects.length && !loading" class="empty">
      <EmptyState
        title="还没有项目"
        description="点右上角「一键体验」会依次建项目、冻结基线、跑一批生成、做评估与缺口诊断，用来把三个阶段的页面都点亮。"
      />
    </section>

    <div v-else class="grid">
      <article v-for="p in projects" :key="p.id" class="card">
        <header class="card__head">
          <div>
            <h3 class="card__name">{{ p.name }}</h3>
            <p class="card__meta">
              <code>{{ p.projectNo }}</code>
              <span class="card__status">{{ p.status }}</span>
            </p>
          </div>
          <span class="card__id">#{{ p.id }}</span>
        </header>

        <!-- 进度：三个阶段的就绪状态一眼可见 -->
        <ul class="steps">
          <li class="step" :class="p.currentBaselineId ? 'step--done' : 'step--todo'">
            <span class="step__dot" />
            <span class="step__label">基线</span>
            <span class="step__state">{{ p.currentBaselineId ? '已冻结' : '未冻结' }}</span>
          </li>
          <li class="step" :class="p.currentRoundId ? 'step--done' : 'step--todo'">
            <span class="step__dot" />
            <span class="step__label">轮次</span>
            <span class="step__state">{{ p.currentRoundId ? `#${p.currentRoundId}` : '未开始' }}</span>
          </li>
          <li class="step" :class="(p.candidateStats?.total ?? 0) > 0 ? 'step--done' : 'step--todo'">
            <span class="step__dot" />
            <span class="step__label">候选</span>
            <span class="step__state">{{ p.candidateStats?.total ?? 0 }} 个</span>
          </li>
        </ul>

        <div class="card__links">
          <button type="button" class="link" @click="go(`/design/projects/${p.id}/baseline`)">
            需求基线 →
          </button>
          <button
            type="button"
            class="link"
            :class="{ 'link--disabled': !p.currentBaselineId }"
            :title="p.currentBaselineId ? '' : '先冻结基线才能生成'"
            @click="p.currentBaselineId && go(`/design/projects/${p.id}/generation`)"
          >
            候选生成 →
          </button>
          <button
            type="button"
            class="link"
            :class="{ 'link--disabled': !(p.candidateStats?.total ?? 0) }"
            :title="(p.candidateStats?.total ?? 0) ? '' : '先生成候选才能评估'"
            @click="openEvaluation(p)"
          >
            评估与编辑 →
          </button>
          <button
            type="button"
            class="link"
            :class="{ 'link--disabled': !p.currentBaselineId }"
            :title="p.currentBaselineId ? '装配证据 → 否决复核 → 分桶 → 名额' : '先冻结基线：决策只读已评估冻结的候选'"
            @click="p.currentBaselineId && go(`/design/projects/${p.id}/decision`)"
          >
            决策 →
          </button>
        </div>
      </article>
    </div>
  </div>
  </main>
</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { requirementApi } from '@/api/requirement'
import { generationApi } from '@/api/generation'
import { evaluationApi, editApi } from '@/api/evaluation'
import { manualCandidateApi } from '@/api/candidate'
import { decisionApi } from '@/api/decision'
import type { ProjectBrief } from '@/api/requirement'
import type { ApiError } from '@/utils/request'

/**
 * 项目列表 —— 阶段 A~D 各页的唯一入口。
 *
 * 在此之前，基线 / 生成 / 评估三个页面都只认 URL 里的 id，用户只能手敲地址进去。
 * 「一键体验」把七个既有 API 串起来（建项目 → 冻结基线 → 开轮 → 生成 → 评估 → 诊断），
 * 不新增后端端点：这些能力本来就有，缺的只是把它们连起来的那条线。
 */
const router = useRouter()
const route = useRoute()

const projects = ref<ProjectBrief[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const seeding = ref(false)
const seedStep = ref('')

// ---------------------------------------------------------------- 新建项目

const showCreate = ref(false)
const creating = ref(false)
/** 从靶点库跳来时带过来的靶点；下拉加载完后才可能命中，先记着 */
const pendingTargetId = ref(0)
const modalityOptions = ref<Array<{ id: number; code: string; name: string }>>([])
const targetOptions = ref<Array<{ id: number; name: string }>>([])
const moaOptions = ref<Array<{ code: string; name: string }>>([])

const form = ref({
  name: '',
  modalityId: 0,
  targetId: 0,
  minCandidates: 3,
  moaCode: '',
  quotaGpuHours: 48,
  quotaWetLab: 8,
  withHardRule: true
})

function toggleCreate() {
  showCreate.value = !showCreate.value
  if (showCreate.value) {
    void prepareForm()
  }
}

/** 从靶点库带过来时自动展开表单并选中靶点：`/projects?targetId=16&create=1` */
function applyQueryPrefill() {
  const targetId = Number(route.query.targetId ?? 0)
  const wantsCreate = route.query.create === '1'
  if (!targetId && !wantsCreate) return

  if (wantsCreate || targetId) showCreate.value = true
  if (targetId) {
    pendingTargetId.value = targetId
    form.value.targetId = targetId
  }
  if (showCreate.value) void prepareForm()
}

/** 下拉选项一次拉齐；失败不阻断页面，只是下拉空着 */
async function prepareForm() {
  if (modalityOptions.value.length) return
  try {
    const [mod, moa, targets] = await Promise.all([
      requirementApi.listLibrary('MODALITY').catch(() => null),
      requirementApi.listLibrary('MOA').catch(() => null),
      requirementApi.listTargets().catch(() => null)
    ])
    modalityOptions.value = (mod?.list ?? []).map((m) => ({ id: m.id, code: m.code, name: m.name }))
    moaOptions.value = (moa?.list ?? []).map((m) => ({ code: m.code, name: m.name }))
    targetOptions.value = (targets?.list ?? []).map((t) => ({ id: t.id, name: t.name }))

    // 默认值：小分子 + EGFR（没有就第一个）；从靶点库带来的靶点优先
    const small = modalityOptions.value.find((m) => /SMALL/i.test(m.code)) ?? modalityOptions.value[0]
    if (small && !form.value.modalityId) form.value.modalityId = small.id
    const fromLibrary = targetOptions.value.find((t) => t.id === pendingTargetId.value)
    const egfr = fromLibrary ?? targetOptions.value.find((t) => t.name === 'EGFR') ?? targetOptions.value[0]
    if (egfr && !form.value.targetId) form.value.targetId = egfr.id
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载下拉选项失败'
  }
}

async function createProject() {
  if (creating.value) return
  creating.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await requirementApi.createProject({
      name: form.value.name,
      modalityId: form.value.modalityId,
      moaCode: form.value.moaCode || undefined,
      minCandidates: form.value.minCandidates,
      quotaGpuHours: form.value.quotaGpuHours,
      quotaWetLab: form.value.quotaWetLab,
      targets: [{ targetId: form.value.targetId, role: 'WILD_TYPE', isPrimary: true }],
      constraintRules: form.value.withHardRule
        ? [{
            ruleType: 'NUMERIC_RANGE',
            severity: 'HARD',
            params: { field: 'MolWt', min: 150, max: 600 },
            weight: 10,
            humanReadable: '分子量 150~600 Da'
          }]
        : undefined
    })
    // 按分子形式补入基线指标：只补缺失项，不覆盖用户调过的权重
    await requirementApi.initMetricsFromModality(created.id).catch(() => null)
    notice.value = `项目已创建（#${created.id}），去补齐基线八块后即可冻结`
    showCreate.value = false
    await loadAll()
    router.push(`/design/projects/${created.id}/baseline`)
  } catch (e) {
    error.value = (e as ApiError)?.message || '创建项目失败'
  } finally {
    creating.value = false
  }
}

const canSeed = computed(() => !seeding.value)

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const page = await requirementApi.listProjects()
    projects.value = page.list ?? []
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载项目列表失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadAll()
  applyQueryPrefill()
})

function go(path: string) {
  router.push(path)
}

/** 从生成页进评估：先查最新批次的保留候选 */
async function openEvaluation(p: ProjectBrief) {
  error.value = ''
  try {
    const runs = await generationApi.list(p.id)
    const latest = runs.list?.[0]
    if (!latest) {
      error.value = '该项目还没有生成批次'
      return
    }
    const logs = await generationApi.candidateLogs(latest.id, 'S7_QUOTA', 'KEPT')
    const candidateId = logs.find((l) => l.candidateId)?.candidateId
    if (!candidateId) {
      error.value = '该批次没有可评估的候选（可能全部被筛掉）'
      return
    }
    router.push({ path: `/design/candidates/${candidateId}/evaluation`, query: { projectId: String(p.id) } })
  } catch (e) {
    error.value = (e as ApiError)?.message || '打开评估页失败'
  }
}

// ---------------------------------------------------------------- 一键体验

/** 用一个真实靶点（EGFR）跑通全链路，所有数据都属于当前登录用户 */
async function runDemo() {
  if (!canSeed.value) return
  seeding.value = true
  error.value = ''
  notice.value = ''
  const stamp = new Date().toTimeString().slice(0, 8).replace(/:/g, '')

  try {
    seedStep.value = '① 查靶点…'
    const modalities = await requirementApi.listLibrary('MODALITY').catch(() => null)
    // 分子形式取第一个可用项（小分子优先，否则任意）
    const modalityId = pickModality(modalities?.list ?? null)

    const targetId = await pickTarget()

    seedStep.value = '② 建项目…'
    const created = await requirementApi.createProject({
      name: `演示项目 ${stamp}`,
      modalityId,
      moaCode: 'INHIBITOR',
      minCandidates: 3,
      quotaGpuHours: 48,
      quotaWetLab: 8,
      targets: [{ targetId, role: 'WILD_TYPE', isPrimary: true }],
      constraintRules: [{
        ruleType: 'NUMERIC_RANGE',
        severity: 'HARD',
        params: { field: 'MolWt', min: 150, max: 600 },
        weight: 10,
        humanReadable: 'MW 150~600'
      }]
    })
    const projectId = created.id

    seedStep.value = '③ 补齐指标…'
    await requirementApi.initMetricsFromModality(projectId)

    seedStep.value = '④ 冻结基线…'
    const preview = await requirementApi.preview(projectId)
    if (!preview.ready) {
      error.value = `基线不完整，无法冻结：${preview.gaps.map((g) => g.message).join('；')}`
      return
    }
    await requirementApi.freeze(projectId, preview.contentHash)

    seedStep.value = '⑤ 开轮次…'
    await requirementApi.openRound(projectId, 'demo')

    seedStep.value = '⑥ 生成候选…'
    const run = await generationApi.submit(projectId, {
      seeds: [
        { ref: 'IBU', smiles: 'CC(C)Cc1ccc(cc1)C(C)C(O)=O' },
        { ref: 'ASP', smiles: 'CC(=O)Oc1ccccc1C(=O)O' }
      ],
      seed: 42,
      idempotencyKey: `demo-${projectId}`
    })

    if (!run.id) {
      error.value = '生成批次没有返回 id'
      return
    }

    seedStep.value = '⑦ 评估冻结…'
    const logs = await generationApi.candidateLogs(run.id, 'S7_QUOTA', 'KEPT')
    const candidateId = logs.find((l) => l.candidateId)?.candidateId
    if (!candidateId) {
      notice.value = '项目已建好且基线已冻结，但本批候选全部被筛掉 —— 可到生成页看筛除原因'
      await loadAll()
      return
    }
    // 决策只认「已评估冻结」的候选，且至少要两个才会出现排名与备选 ——
    // 只冻结第一个的话，决策页只剩一行，名额与备选这套机制等于没演示到
    const keptIds = Array.from(new Set(logs.map((l) => l.candidateId).filter(Boolean))) as number[]
    let frozen = 0
    for (const id of keptIds) {
      try {
        await evaluationApi.freeze(id)
        frozen++
      } catch {
        // 单个候选冻结失败不该让整条演示中断：决策会把它当作「未冻结」排除
      }
    }
    if (!frozen) await evaluationApi.freeze(candidateId)

    seedStep.value = '⑧ 画布演示：建候选 + 派生一版…'
    // 演示「二维画布 → 候选 → 版本树」这条路：结构与指标全部由后端引擎实算。
    // 派生版用的是阿司匹林的同分异构位次（邻位 → 对位），InChIKey 会变，版本树就有了两个节点。
    let canvasCandidateId = 0
    let versionCandidateId = 0
    try {
      const canvas = await manualCandidateApi.create(projectId, 'CC(=O)Oc1ccccc1C(=O)O', '画布·阿司匹林')
      canvasCandidateId = canvas.candidateId
      await evaluationApi.freeze(canvasCandidateId)
      const version = await manualCandidateApi.createVersion(
        canvasCandidateId,
        'CC(=O)Oc1ccc(cc1)C(=O)O',
        '乙酰氧基从邻位挪到对位（演示写回）',
        'C4'
      )
      versionCandidateId = version.candidateId
      await evaluationApi.freeze(versionCandidateId)
    } catch (e) {
      // 引擎不可用时不该让整条演示失败：前面几步已经产生了可看的数据
      notice.value = `画布演示跳过（${(e as ApiError)?.message || '引擎不可用'}）；`
    }

    seedStep.value = '⑨ 缺口诊断…'
    const gaps = await editApi.diagnose(versionCandidateId || canvasCandidateId || candidateId)

    seedStep.value = '⑩ 生成决策…'
    // 演示的终点就是决策：名额、排名、否决理由、回放指纹全在这一页
    const decision = await decisionApi.create(projectId, { decisionNote: '一键体验生成的决策' })

    notice.value =
      (notice.value || '') +
      `完成：项目 #${projectId}、冻结 ${frozen} 个生成候选` +
      (versionCandidateId ? ` + 画布候选 #${canvasCandidateId} → 派生 v2 #${versionCandidateId}` : '') +
      `、诊断 ${gaps.gaps.length} 项可修缺口、决策 ${decision.decisionNo}（名额 ${decision.capacity ?? '—'}，` +
      `选中 ${decision.selectedCount}，备选 ${decision.backupCount}）`
    await loadAll()
    router.push(`/design/projects/${projectId}/decision`)
  } catch (e) {
    error.value = `${seedStep.value}失败：${(e as ApiError)?.message || '未知错误'}`
  } finally {
    seeding.value = false
    seedStep.value = ''
  }
}

/** 取 EGFR；没有就用第一个靶点。靶点是项目必填项 */
async function pickTarget(): Promise<number> {
  const res = await requirementApi.listTargets()
  const list = res.list ?? []
  const picked = list.find((t) => t.name === 'EGFR') ?? list[0]
  if (!picked) {
    throw new Error('靶点库里没有可用靶点，无法建项目')
  }
  return picked.id
}
/** 分子形式下拉：优先小分子（当前只有小分子能做真指标计算） */
function pickModality(items: { code: string; name: string; id: number }[] | null): number {
  if (!items || !items.length) return 1
  const small = items.find((i) => /SMALL|小分子/i.test(`${i.code} ${i.name}`))
  return (small ?? items[0]).id
}

</script>

<style lang="scss" scoped>
.pj {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.pj__main {
  flex: 1;
  padding: $spacing-lg $spacing-xl $spacing-2xl;
}

.page {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}
.empty {
  padding: $spacing-2xl 0;
}

.create {
  padding: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &__head {
    margin-bottom: $spacing-md;
  }

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__hint {
    max-width: 760px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
    gap: $spacing-sm;
  }

  &__check {
    display: flex;
    gap: $spacing-sm;
    align-items: flex-start;
    margin-top: $spacing-md;
    font-size: $font-size-sm;
    color: $color-text-soft;
    cursor: pointer;

    em {
      display: block;
      margin-top: 2px;
      font-size: $font-size-xs;
      font-style: normal;
      color: $color-text-faint;
    }
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-md;
    align-items: center;
    margin-top: $spacing-md;
    padding-top: $spacing-md;
    border-top: 1px solid $color-border;
  }

  &__tip {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;

  &--wide {
    grid-column: span 2;
  }

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__control {
    padding: 7px 9px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-sm;
    color: $color-text;
    background: $color-surface;
  }
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: $spacing-md;
}

.card {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
  padding: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;
  box-shadow: $shadow-card;
  transition: box-shadow $transition-fast;

  &:hover {
    box-shadow: $shadow-card-hover;
  }

  &__head {
    display: flex;
    gap: $spacing-sm;
    align-items: flex-start;
    justify-content: space-between;
  }

  &__name {
    margin: 0 0 4px;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__meta {
    display: flex;
    gap: $spacing-sm;
    margin: 0;
    font-size: $font-size-xs;
    color: $color-text-faint;

    code {
      font-family: monospace;
    }
  }

  &__status {
    padding: 0 6px;
    border-radius: $radius-pill;
    background: $color-surface-sunken;
  }

  &__id {
    font-family: monospace;
    font-size: $font-size-xs;
    color: $text-muted;
  }

  &__links {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-xs;
    margin-top: auto;
    padding-top: $spacing-sm;
    border-top: 1px solid $color-border-soft;
  }
}

.steps {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.step {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
  font-size: $font-size-sm;

  &__dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: $text-muted;
  }

  &__label {
    min-width: 40px;
    color: $color-text-soft;
  }

  &__state {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &--done {
    .step__dot {
      background: $success-color;
    }

    .step__state {
      color: $success-color;
    }
  }

  &--todo {
    .step__dot {
      background: rgba($text-muted, 0.5);
    }
  }
}

.link {
  padding: 4px 10px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  font-size: $font-size-xs;
  color: $color-brand-strong;
  background: $color-surface;
  cursor: pointer;

  &:hover:not(.link--disabled) {
    border-color: $color-brand;
    background: $color-brand-softer;
  }

  &--disabled {
    color: $text-muted;
    cursor: not-allowed;
  }
}
</style>
