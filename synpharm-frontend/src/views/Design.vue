<template>
  <div class="mw">
    <Sidebar />
    <main class="mw__main">
      <div class="page">
        <PageHeader
          icon="✏️"
          title="分子精修台"
          subtitle="二维画布：从零画或改结构 → 浏览器内 Indigo 实算真值 → 按冻结基线的硬约束当场判定"
        >
          <template #actions>
            <button type="button" class="btn" :disabled="loading" @click="loadAll">
              {{ loading ? '加载中…' : '刷新' }}
            </button>
            <button
              type="button"
              class="btn"
              :disabled="!editorReady || analyzing"
              :title="editorReady ? '用本地 Indigo 引擎实算当前画布上的结构' : '编辑器还在启动'"
              @click="analyzeCurrent()"
            >
              {{ analyzing ? '实算中…' : '引擎实算' }}
            </button>
            <button type="button" class="btn" :disabled="!originSmiles" @click="loadCandidateToCanvas">
              载入候选结构
            </button>
            <button
              type="button"
              class="btn btn--primary"
              :disabled="savingCandidate || !currentSmiles || !projectId"
              :title="saveHint"
              @click="saveAsCandidate"
            >
              {{ savingCandidate ? '保存中…' : '存为新候选' }}
            </button>
          </template>
        </PageHeader>

        <FlowSteps :project-id="projectId || null" current="evaluation" :candidate-id="candidateId || null" />

        <p v-if="error" class="banner banner--danger">{{ error }}</p>
        <p v-if="notice" class="banner banner--ok">{{ notice }}</p>

        <!-- 选择条：项目 → 批次 → 候选，三级都来自真实接口 -->
        <section class="picker">
          <label class="field">
            <span class="field__label">项目</span>
            <select v-model.number="projectId" class="field__control" @change="onProjectChange">
              <option :value="0" disabled>— 选择项目 —</option>
              <option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">生成批次</span>
            <select v-model.number="runId" class="field__control" :disabled="!runs.length" @change="onRunChange">
              <option :value="0" disabled>— 选择批次 —</option>
              <option v-for="r in runs" :key="r.id" :value="r.id">
                {{ r.runNo }} · {{ statusLabel(r.status) }} · {{ r.strategy }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">候选（配额保留 + 画布新建）</span>
            <select
              v-model.number="candidateId"
              class="field__control"
              :disabled="!pickerItems.length"
              @change="onCandidateChange"
            >
              <option :value="0" disabled>— 选择候选 —</option>
              <option v-for="k in pickerItems" :key="k.candidateId" :value="k.candidateId">
                #{{ k.candidateId }}　{{ shortSmiles(k.smiles) }}
              </option>
            </select>
          </label>

          <span class="picker__meta">
            <template v-if="project">基线 {{ snapshot?.snapshotNo ?? '未冻结' }} · 候选 {{ pickerItems.length }} 个</template>
            <template v-else>先选一个项目</template>
          </span>
        </section>

        <!-- 没有项目时不再把整页挡掉：画布与 Indigo 实算不依赖后端，从零画一个分子也该能用 -->
        <section v-if="!projects.length && !loading" class="banner banner--warn mw__hint">
          还没有设计项目 —— 下面的画布可以直接从零画结构、用 Indigo 实算分子式/分子量/InChIKey；
          要让「硬约束判定 / 评估 / 缺口 / 版本树」生效，先建一个项目并冻结基线。
          <button type="button" class="btn btn--sm" @click="router.push('/projects')">去设计项目列表 →</button>
        </section>

        <div class="mw__grid">
          <!-- ==================== 左：分子画布 ==================== -->
          <section class="panel canvas">
            <header class="canvas__head">
              <h3 class="canvas__title">分子画布</h3>
              <span class="canvas__hint">
                {{ editorReady ? '可直接在画布上改结构，改完点「引擎实算」' : editorStateText }}
              </span>
            </header>

            <div class="canvas__stage">
              <MoleculeEditor ref="editorRef" @ready="onEditorReady" @error="onEditorError" />
            </div>

            <div class="canvas__foot">
              <div class="canvas__smiles">
                <span class="canvas__smiles-label">SMILES</span>
                <code class="canvas__smiles-text" :title="currentSmiles">{{ currentSmiles || '—' }}</code>
              </div>
              <input
                v-model.trim="newCandidateName"
                type="text"
                maxlength="200"
                class="canvas__name"
                placeholder="新候选名称（可空）"
                :disabled="!projectId"
              />
              <button type="button" class="btn btn--sm" :disabled="!currentSmiles" @click="copySmiles">
                复制
              </button>
            </div>

            <!-- 写回：把改动落成版本树上的下一版（父版本保留，历史只增不删） -->
            <div v-if="candidateId" class="canvas__version">
              <input
                v-model.trim="editDescribe"
                type="text"
                maxlength="500"
                class="canvas__name"
                placeholder="这一版改了什么（必填，会进版本树）"
              />
              <button
                type="button"
                class="btn btn--sm btn--primary"
                :disabled="savingVersion || !editDescribe || !canSaveVersion"
                :title="versionHint"
                @click="saveAsVersion"
              >
                {{ savingVersion ? '保存中…' : '存为新版本' }}
              </button>
              <span class="canvas__version-hint">{{ versionHint }}</span>
            </div>

            <!-- 引擎实算结果：本页唯一不依赖后端的真值来源 -->
            <div class="engine">
              <div class="engine__head">
                <span class="tag" :class="engine ? 'tag--success' : 'tag--neutral'">
                  {{ engine ? 'Indigo 实算' : '尚未实算' }}
                </span>
                <span
                  v-if="engine && baselineAnalysis"
                  class="tag"
                  :class="sameMolecule ? 'tag--info' : 'tag--warning'"
                >
                  {{ sameMolecule ? '与载入时同一分子' : '已改动：和载入时不是同一分子' }}
                </span>
                <span v-if="analysisError" class="engine__err">{{ analysisError }}</span>
              </div>

              <div class="engine__grid">
                <div class="engine__cell">
                  <span class="engine__label">分子式</span>
                  <b class="engine__value">{{ engine?.formula ?? '—' }}</b>
                </div>
                <div class="engine__cell">
                  <span class="engine__label">分子量</span>
                  <b class="engine__value">{{ engine ? engine.molecularWeight.toFixed(2) + ' Da' : '—' }}</b>
                </div>
                <div class="engine__cell">
                  <span class="engine__label">相对载入时</span>
                  <b class="engine__value" :class="{ 'engine__value--delta': !sameMolecule }">
                    {{ deltaMwText }}
                  </b>
                </div>
                <div class="engine__cell">
                  <span class="engine__label">载入时分子量</span>
                  <b class="engine__value">
                    {{ baselineAnalysis ? baselineAnalysis.molecularWeight.toFixed(2) + ' Da' : '—' }}
                  </b>
                </div>
                <div class="engine__cell engine__cell--wide">
                  <span class="engine__label">InChIKey</span>
                  <b class="engine__value engine__value--mono" :title="engine?.inchikey">
                    {{ engine?.inchikey ?? '—' }}
                  </b>
                </div>
              </div>

              <p class="engine__note">
                分子式 / 分子量 / InChIKey 由浏览器内的 Indigo 引擎实算，不是估算值，也不需要后端。
                它决定「这个结构到底是不是你以为的那个分子」—— 改掉一个甲基，InChIKey 一定会变。
              </p>
            </div>
          </section>

          <!-- ==================== 右：判定与代价 ==================== -->
          <section class="side">
            <!-- 1. 硬约束：真值来自当前冻结基线 -->
            <div class="panel card">
              <header class="card__head">
                <h3 class="card__title">硬约束判定</h3>
                <span class="tag tag--neutral">{{ snapshot ? `基线 ${snapshot.snapshotNo}` : '无冻结基线' }}</span>
              </header>

              <p v-if="!project" class="card__empty">
                还没有选项目 —— 上面的画布和 Indigo 实算照常可用，只是没有基线可以对照。
                选了项目之后，这里会按冻结基线的硬约束逐条判定。
              </p>
              <p v-else-if="!snapshot" class="card__empty">
                这个项目还没有冻结基线。冻结之后约束才定稿 —— 生成与评估都只读快照。
              </p>
              <p v-else-if="!ruleViews.length" class="card__empty">
                冻结基线里没有硬约束。
              </p>
              <ul v-else class="rules">
                <li v-for="r in ruleViews" :key="r.key" class="rules__row">
                  <span class="tag" :class="verdictTone(r.verdict)">{{ verdictLabel(r.verdict) }}</span>
                  <span class="rules__text">{{ r.text }}</span>
                  <span class="rules__actual">{{ r.actual }}</span>
                </li>
              </ul>

              <p class="card__note">
                在画布上改完结构后点「引擎实算」，这里的判定会跟着重算。
                <template v-if="unjudgedCount > 0">
                  有 {{ unjudgedCount }} 条约束本地算不了（引擎只给分子量），需由后端评估判定，这里标「需后端」。
                </template>
              </p>
            </div>

            <!-- 2. 真实评估冻结 -->
            <div class="panel card">
              <header class="card__head">
                <h3 class="card__title">评估冻结</h3>
                <button
                  v-if="candidateId"
                  type="button"
                  class="btn btn--sm"
                  :disabled="freezing"
                  @click="doFreeze"
                >
                  {{ freezing ? '评估中…' : freeze ? '重新评估' : '评估并冻结' }}
                </button>
              </header>

              <p v-if="!candidateId" class="card__empty">先在上面选一个候选。</p>
              <div v-else-if="!freeze" class="card__empty">
                <p>还没有评估冻结快照。编辑与决策都以快照为输入。</p>
              </div>
              <EvaluationFreezePanel v-else :freeze="freeze" />
            </div>

            <!-- 3. 缺口与可做动作 -->
            <div class="panel card">
              <header class="card__head">
                <h3 class="card__title">缺口与动作</h3>
                <button
                  v-if="freeze && !gapReport"
                  type="button"
                  class="btn btn--sm"
                  :disabled="diagnosing"
                  @click="diagnose"
                >
                  {{ diagnosing ? '诊断中…' : '生成诊断' }}
                </button>
              </header>

              <p v-if="!gapReport" class="card__empty">
                缺口诊断需要先有评估冻结快照 —— 没分数就无从判断哪一维不达标。
              </p>
              <GapPanel v-else :report="gapReport" :selected="pendingAction" @preview="previewImpact" />
            </div>

            <!-- 4. 影响面：改一版的代价 -->
            <div v-if="impact" class="panel card">
              <header class="card__head">
                <h3 class="card__title">影响面：{{ actionLabel(impact.actionCode) }}</h3>
                <span class="tag" :class="`tag--${guardMeta(impact.guardResult).tone}`">
                  {{ guardMeta(impact.guardResult).label }}
                </span>
              </header>

              <p class="card__hint">{{ guardMeta(impact.guardResult).hint }}</p>
              <p v-if="impact.blockReason" class="banner banner--danger">{{ impact.blockReason }}</p>

              <ul class="impacts">
                <li v-for="(p, i) in impact.protectedOnes" :key="i" class="impacts__row">
                  <span class="tag" :class="p.risk === 'MUST_DROP' ? 'tag--danger' : 'tag--warning'">
                    {{ p.risk === 'MUST_DROP' ? '会下降' : '需观察' }}
                  </span>
                  <span class="impacts__dim">
                    {{ p.dimension === '*' ? '全部已达标维度' : dimensionLabel(p.dimension) }}
                  </span>
                  <span class="impacts__note">{{ p.note }}</span>
                </li>
              </ul>

              <p class="card__note">
                这里只算「动了会不会伤到已经达标的维度」，不预测具体涨跌数值 ——
                动作到指标的量化模型还没有，硬编一个反而会误导。
              </p>
            </div>

            <!-- 5. 版本树 -->
            <div class="panel card">
              <header class="card__head">
                <h3 class="card__title">版本树</h3>
              </header>
              <CandidateTree v-if="tree" :tree="tree" @focus="focusCandidate" />
              <p v-else class="card__empty">这个候选还没有版本关系（它是自己的根）。</p>
            </div>
          </section>
        </div>

        <p class="mw__footer">
          本页所有数字都来自真实接口：候选来自生成批次的配额保留名单，分数来自评估冻结，
          缺口来自诊断报告，硬约束来自当前冻结基线；分子式 / 分子量 / InChIKey 由浏览器内 Indigo 引擎实算。
          <b>把精修结果写回候选</b>需要后端 <code>/v1/molecules/edit</code>（尚未部署），
          所以这里只做「看得见结果」的判定，不假装能保存。
        </p>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 单分子精修台（/design）。
 *
 * 与「评估与编辑」页的分工：
 *   · 评估页以**流程**为中心 —— 冻结 → 诊断 → 挑动作 → 影响面，四个页签各管一件事；
 *   · 精修台以**一个分子**为中心 —— 画布在左，判定与代价在右，改完马上知道行不行。
 *
 * 数据来源（全部真实，没有 mock）：
 *   项目        requirementApi.listProjects / getProject
 *   冻结基线    requirementApi.current            → 硬约束逐条本地判定
 *   候选        generationApi.list + candidateLogs(stage=S7_QUOTA, outcome=KEPT)
 *   评估/缺口   evaluationApi.latest / editApi.gaps / previewImpact / tree
 *   结构真值    MoleculeEditor（浏览器内 Indigo，实算分子式 / 分子量 / InChIKey）
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import FlowSteps from '@/components/design/FlowSteps.vue'
import MoleculeEditor from '@/components/design/MoleculeEditor.vue'
import EvaluationFreezePanel from '@/components/design/EvaluationFreezePanel.vue'
import GapPanel from '@/components/design/GapPanel.vue'
import CandidateTree from '@/components/design/CandidateTree.vue'
import { requirementApi } from '@/api/requirement'
import type { BaselineSnapshot, ProjectBrief } from '@/api/requirement'
import { generationApi } from '@/api/generation'
import type { GenerationCandidateLog, GenerationRun } from '@/api/generation'
import { editApi, evaluationApi, actionLabel, dimensionLabel, guardMeta } from '@/api/evaluation'
import { manualCandidateApi } from '@/api/candidate'
import type {
  CandidateTree as CandidateTreeData,
  EditGapReport,
  EditImpact,
  EvaluationFreeze
} from '@/api/evaluation'
import type { EngineAnalysis } from '@/types/design'
import type { ApiError } from '@/utils/request'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const notice = ref('')

const projects = ref<ProjectBrief[]>([])
const projectId = ref(0)
const project = ref<ProjectBrief | null>(null)
const snapshot = ref<BaselineSnapshot | null>(null)

const runs = ref<GenerationRun[]>([])
const runId = ref(0)
const kept = ref<GenerationCandidateLog[]>([])
/**
 * 画布上建立的候选。单独一个列表而不是塞进 `kept`：
 * `kept` 是生成批次的「配额保留」日志行，伪造一条会让「这个分子是从漏斗里出来的」变成假话。
 */
const manual = ref<Array<{ candidateId: number; smiles: string }>>([])
const candidateId = ref(0)

/** 候选下拉的真正数据源：生成保留 + 画布新建 */
const pickerItems = computed<Array<{ candidateId: number; smiles: string }>>(() => {
  const out: Array<{ candidateId: number; smiles: string }> = []
  for (const k of kept.value) {
    if (k.candidateId && !out.some((x) => x.candidateId === k.candidateId)) {
      out.push({ candidateId: k.candidateId, smiles: k.smiles ?? '' })
    }
  }
  for (const m of manual.value) {
    if (!out.some((x) => x.candidateId === m.candidateId)) out.push(m)
  }
  return out
})

const freeze = ref<EvaluationFreeze | null>(null)
const freezing = ref(false)
const gapReport = ref<EditGapReport | null>(null)
const diagnosing = ref(false)
const tree = ref<CandidateTreeData | null>(null)
const impact = ref<EditImpact | null>(null)
const pendingAction = ref('')

const editorRef = ref<InstanceType<typeof MoleculeEditor> | null>(null)
const editorReady = ref(false)
const editorErrorText = ref('')
const analyzing = ref(false)
const engine = ref<EngineAnalysis | null>(null)
const analysisError = ref('')

/**
 * 载入候选时实算出的「原分子真值」。
 * 生成日志里的 `inchikey` 常常是空的（规则式导入路径不做结构哈希），
 * 所以「改没改」不能靠它比对 —— 而是把载入那一刻的实算结果记下来当基线。
 */
const baselineAnalysis = ref<EngineAnalysis | null>(null)

/* ---------------------------------------------------------------- 文案 */

const STATUS_LABEL: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '已完成',
  PARTIAL: '部分成功',
  FAILED: '失败',
  CANCELLED: '已取消'
}

const statusLabel = (s?: string): string => STATUS_LABEL[s ?? ''] ?? s ?? '—'

const editorStateText = computed(() => {
  if (editorReady.value) return '编辑器就绪'
  if (editorErrorText.value) return '编辑器不可用'
  return '编辑器加载中…'
})

/* ---------------------------------------------------------------- 派生 */

/** 当前候选的原始 SMILES（生成保留的分子或画布新建的都算） */
const originSmiles = computed(() => {
  const fromKept = kept.value.find((k) => k.candidateId === candidateId.value)?.smiles
  if (fromKept) return fromKept
  return manual.value.find((m) => m.candidateId === candidateId.value)?.smiles ?? ''
})

/** 画布上的 SMILES：引擎实算结果优先，否则用原候选 */
const currentSmiles = computed(() => engine.value?.smiles || originSmiles.value || '')

/** 当前结构是否仍是载入时那个分子 —— InChIKey 相同才是同一分子 */
const sameMolecule = computed(() => {
  if (!engine.value || !baselineAnalysis.value) return true
  return engine.value.inchikey === baselineAnalysis.value.inchikey
})

/** 分子量变化量：精修最直观的即时反馈（undefined 表示还没两边都算过） */
const deltaMw = computed<number | null>(() => {
  if (!engine.value || !baselineAnalysis.value) return null
  return engine.value.molecularWeight - baselineAnalysis.value.molecularWeight
})

const deltaMwText = computed(() => {
  const d = deltaMw.value
  if (d === null) return '—'
  if (Math.abs(d) < 0.005) return '0.00 Da'
  return `${d > 0 ? '+' : ''}${d.toFixed(2)} Da`
})

function shortSmiles(s?: string): string {
  if (!s) return '（无结构）'
  return s.length <= 28 ? s : `${s.slice(0, 26)}…`
}

/* ---------------------------------------------------------------- 硬约束判定 */

type Verdict = 'PASS' | 'FAIL' | 'NA' | 'PENDING'

interface RuleView {
  key: string
  text: string
  verdict: Verdict
  actual: string
}

/** 引擎能算、因而可以本地判定的字段别名（后端契约里是 MolWt） */
const MOLWT_FIELDS = new Set(['MolWt', 'MW', 'MOLWT', 'MOL_WEIGHT', 'MOLECULAR_WEIGHT'])

/**
 * 快照里的 `paramsJson` 是**二次编码的 JSON 文本**（阶段 C 已踩过这个坑），
 * 三形态都要接：字符串 / 对象 / 缺失。
 */
function readParams(raw: unknown): Record<string, unknown> {
  if (!raw) return {}
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as Record<string, unknown>
    } catch {
      return {}
    }
  }
  if (typeof raw === 'object') return raw as Record<string, unknown>
  return {}
}

const ruleViews = computed<RuleView[]>(() => {
  const box = snapshot.value?.hardRules as { items?: unknown[] } | null | undefined
  const items = Array.isArray(box?.items) ? box.items : []
  const mw = engine.value?.molecularWeight

  return items.map((raw, index) => {
    const item = (raw ?? {}) as Record<string, unknown>
    const ruleType = String(item.ruleType ?? '')
    const text = String(item.humanReadable ?? ruleType ?? `规则 ${index + 1}`)
    const params = readParams(item.paramsJson ?? item.params)
    const field = String(params.field ?? '')
    const key = `${String(item.ruleId ?? index)}-${ruleType}`

    if (ruleType !== 'NUMERIC_RANGE' || !MOLWT_FIELDS.has(field)) {
      return { key, text, verdict: 'NA' as Verdict, actual: '需后端' }
    }
    if (mw === undefined || mw === null) {
      return { key, text, verdict: 'PENDING' as Verdict, actual: '待实算' }
    }

    const min = Number(params.min)
    const max = Number(params.max)
    const aboveMin = Number.isFinite(min) ? mw >= min : true
    const belowMax = Number.isFinite(max) ? mw <= max : true

    return {
      key,
      text,
      verdict: (aboveMin && belowMax ? 'PASS' : 'FAIL') as Verdict,
      actual: `${mw.toFixed(2)} Da`
    }
  })
})

const unjudgedCount = computed(() => ruleViews.value.filter((r) => r.verdict === 'NA').length)

function verdictTone(v: Verdict): string {
  if (v === 'PASS') return 'tag--success'
  if (v === 'FAIL') return 'tag--danger'
  return 'tag--neutral'
}

function verdictLabel(v: Verdict): string {
  if (v === 'PASS') return '通过'
  if (v === 'FAIL') return '违反'
  if (v === 'PENDING') return '待实算'
  return '需后端'
}

/* ---------------------------------------------------------------- 加载 */

function clearCandidate() {
  freeze.value = null
  gapReport.value = null
  tree.value = null
  impact.value = null
  pendingAction.value = ''
  engine.value = null
  baselineAnalysis.value = null
  analysisError.value = ''
}

async function loadProjects() {
  const res = await requirementApi.listProjects(1, 50)
  projects.value = res.list ?? []
}

async function loadProjectContext() {
  project.value = null
  snapshot.value = null
  runs.value = []
  runId.value = 0
  kept.value = []
  candidateId.value = 0
  clearCandidate()

  if (!projectId.value) return

  const [detail, snap, runPage] = await Promise.all([
    requirementApi.getProject(projectId.value).catch(() => null),
    requirementApi.current(projectId.value).catch(() => null),
    generationApi.list(projectId.value, 1, 20).catch(() => null)
  ])
  project.value = detail
  snapshot.value = snap
  runs.value = runPage?.list ?? []

  const preferred = Number(route.query.runId ?? 0)
  const first = runs.value.find((r) => r.id === preferred) ?? runs.value[0]
  if (first) {
    runId.value = first.id
    await loadCandidates()
  }
}

async function loadCandidates() {
  kept.value = []
  candidateId.value = 0
  clearCandidate()
  if (!runId.value) return

  // 只有「配额保留」的分子才进候选池 —— 被筛掉的没有可评估对象
  kept.value = await generationApi
    .candidateLogs(runId.value, 'S7_QUOTA', 'KEPT')
    .catch(() => [])

  const preferred = Number(route.query.candidateId ?? 0)
  const first = kept.value.find((k) => k.candidateId === preferred) ?? kept.value[0]
  if (first?.candidateId) {
    candidateId.value = first.candidateId
    await loadCandidateContext()
  }
}

async function loadCandidateContext() {
  clearCandidate()
  if (!candidateId.value) return

  const [f, g, t] = await Promise.all([
    evaluationApi.latest(candidateId.value).catch(() => null),
    editApi.gaps(candidateId.value).catch(() => null),
    editApi.tree(candidateId.value).catch(() => null)
  ])
  freeze.value = f
  gapReport.value = g
  tree.value = t

  loadCandidateToCanvas()
}

/* ---------------------------------------------------------------- 画布与引擎 */

function onEditorReady() {
  editorReady.value = true
  editorErrorText.value = ''
  loadCandidateToCanvas()
}

function onEditorError(message: string) {
  editorErrorText.value = message
}

/** 把当前候选的 SMILES 推进画布，然后立刻实算一次并记为原分子基线 */
function loadCandidateToCanvas() {
  const smiles = originSmiles.value
  if (!smiles || !editorReady.value) return
  editorRef.value?.setMolecule(smiles)
  void analyzeCurrent(smiles, true)
}

/**
 * 引擎实算。
 * 默认读**画布上的当前结构**（用户可能刚改过），只有显式传入时才用外部 SMILES。
 * 传入 `capture: true` 时把结果同时记为「原分子基线」（只在载入候选时用）。
 */
async function analyzeCurrent(smilesOverride?: string, capture = false) {
  if (!editorRef.value || !editorReady.value) return
  analyzing.value = true
  analysisError.value = ''
  try {
    const smiles = smilesOverride ?? (await editorRef.value.getSmiles())
    if (!smiles) {
      engine.value = null
      return
    }
    const results = await editorRef.value.analyzeBatch([smiles])
    engine.value = results?.[0] ?? null
    if (capture) baselineAnalysis.value = engine.value
  } catch (e) {
    analysisError.value = (e as Error)?.message || '引擎实算失败'
    engine.value = null
  } finally {
    analyzing.value = false
  }
}

async function copySmiles() {
  if (!currentSmiles.value) return
  notice.value = ''
  try {
    await navigator.clipboard.writeText(currentSmiles.value)
    notice.value = 'SMILES 已复制到剪贴板'
  } catch {
    error.value = '浏览器拒绝写入剪贴板'
  }
}

/* ---------------------------------------------------------------- 画布 → 候选 */

const savingCandidate = ref(false)
const newCandidateName = ref('')

const saveHint = computed(() => {
  if (!projectId.value) return '先在上面选一个项目：候选必须挂在某个项目/轮次下'
  if (!currentSmiles.value) return '画布上还没有结构'
  return '把画布上的结构存成项目当前轮次的候选，并立刻评估冻结'
})

/**
 * 存为新候选 → 直接评估冻结。
 *
 * 存完立刻冻结，是因为「一个没有分数的候选」在这里等于只写了一半的记录：
 * 用户画结构的目的就是要看它过不过硬约束、缺口在哪。
 */
async function saveAsCandidate() {
  if (!projectId.value || !currentSmiles.value) return
  savingCandidate.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await manualCandidateApi.create(projectId.value, currentSmiles.value, newCandidateName.value)
    if (!manual.value.some((m) => m.candidateId === created.candidateId)) {
      manual.value.push({ candidateId: created.candidateId, smiles: created.smiles || currentSmiles.value })
    }
    candidateId.value = created.candidateId
    newCandidateName.value = ''
    notice.value = created.existed
      ? `这个结构已经存在（候选 #${created.candidateId}，${created.formula ?? created.inchikey ?? ''}），已为你切过去`
      : `已建立候选 #${created.candidateId}（${created.formula ?? ''} ${created.mw ?? ''} Da，引擎返回 ${
          created.metricCount ?? 0
        } 项指标）`
    await loadCandidateContext()
    await doFreeze()
  } catch (e) {
    error.value = (e as ApiError)?.message || '保存候选失败'
  } finally {
    savingCandidate.value = false
  }
}

/* ---------------------------------------------------------------- 画布 → 新版本 */

const savingVersion = ref(false)
const editDescribe = ref('')

/** 结构真的变了才允许写回：InChIKey 相同说明这次「编辑」没改到分子本身 */
const canSaveVersion = computed(() => {
  const now = engine.value?.inchikey
  const before = baselineAnalysis.value?.inchikey
  return Boolean(now && before && now !== before)
})

const versionHint = computed(() => {
  if (!candidateId.value) return '先选一个候选：新版本要挂在它下面'
  if (!engine.value) return '先点「引擎实算」'
  if (!canSaveVersion.value) return '结构与当前版本相同，没有可写的改动'
  return `将在版本树里生成 #${candidateId.value} 的下一版；父版本保留，历史只增不删`
})

/**
 * 存为新版本（写回）。
 *
 * 与「存为新候选」的区别：新候选是一棵新树的根，而这里是同一个分子的下一版 ——
 * 版本树、缺口诊断、影响面守卫都靠这条父子关系说话。
 * 存完立刻冻结，因为版本接口会把 needsReEval 置 1，带着旧分数进决策等于拿上一个分子下单。
 */
async function saveAsVersion() {
  if (!candidateId.value || !canSaveVersion.value || !editDescribe.value) return
  savingVersion.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await manualCandidateApi.createVersion(
      candidateId.value,
      currentSmiles.value,
      editDescribe.value
    )
    manual.value.push({ candidateId: created.candidateId, smiles: created.smiles || currentSmiles.value })
    candidateId.value = created.candidateId
    editDescribe.value = ''
    notice.value = `已生成版本 v${created.versionNo}（候选 #${created.candidateId}，${created.formula ?? ''} ${
      created.mw ?? ''
    } Da）`
    await loadCandidateContext()
    await doFreeze()
    await syncQuery()
  } catch (e) {
    error.value = (e as ApiError)?.message || '写回版本失败'
  } finally {
    savingVersion.value = false
  }
}

async function doFreeze() {
  if (!candidateId.value) return
  freezing.value = true
  error.value = ''
  notice.value = ''
  try {
    freeze.value = await evaluationApi.freeze(candidateId.value)
    notice.value = `已冻结 ${freeze.value.freezeNo}（权重覆盖率 ${Math.round(
      (freeze.value.weightCoverage ?? 0) * 100
    )}%）`
    gapReport.value = await editApi.gaps(candidateId.value).catch(() => null)
    tree.value = await editApi.tree(candidateId.value).catch(() => null)
  } catch (e) {
    error.value = (e as ApiError)?.message || '评估失败'
  } finally {
    freezing.value = false
  }
}

async function diagnose() {
  if (!candidateId.value) return
  diagnosing.value = true
  error.value = ''
  try {
    const report = await editApi.diagnose(candidateId.value)
    gapReport.value = report
    notice.value = `诊断完成：${report.gaps.length} 项可修缺口、${report.unfixable.length} 项修不了`
    tree.value = await editApi.tree(candidateId.value).catch(() => null)
  } catch (e) {
    error.value = (e as ApiError)?.message || '诊断失败'
  } finally {
    diagnosing.value = false
  }
}

async function previewImpact(actionCode: string) {
  if (!candidateId.value) return
  pendingAction.value = actionCode
  error.value = ''
  try {
    impact.value = await editApi.previewImpact(candidateId.value, actionCode)
  } catch (e) {
    error.value = (e as ApiError)?.message || '影响面评估失败'
  }
}

/* ---------------------------------------------------------------- 切换 */

/** 把当前选择写回 URL：刷新不丢上下文，也方便贴给别人 */
async function syncQuery() {
  const query: Record<string, string> = {}
  if (projectId.value) query.projectId = String(projectId.value)
  if (runId.value) query.runId = String(runId.value)
  if (candidateId.value) query.candidateId = String(candidateId.value)
  await router.replace({ path: '/design', query }).catch(() => undefined)
}

async function onProjectChange() {
  await loadProjectContext()
  await syncQuery()
}

async function onRunChange() {
  await loadCandidates()
  await syncQuery()
}

async function onCandidateChange() {
  await loadCandidateContext()
  await syncQuery()
}

/** 点版本树里的节点：同一批次内直接切，跨批次交给评估页 */
async function focusCandidate(id: number) {
  if (!id || id === candidateId.value) return
  if (!pickerItems.value.some((k) => k.candidateId === id)) {
    router.push({ path: `/design/candidates/${id}/evaluation`, query: { projectId: String(projectId.value) } })
    return
  }
  candidateId.value = id
  await onCandidateChange()
}

/* ---------------------------------------------------------------- 生命周期 */

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    if (!projects.value.length) await loadProjects()
    if (!projectId.value) {
      const fromQuery = Number(route.query.projectId ?? 0)
      const exists = projects.value.some((p) => p.id === fromQuery)
      projectId.value = exists ? fromQuery : projects.value[0]?.id ?? 0
    }
    await loadProjectContext()
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<style lang="scss" scoped>
.mw {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.mw__main {
  flex: 1;
  min-width: 0;
}

.mw__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: $spacing-lg;
  align-items: start;
  margin-top: $spacing-md;
}

.mw__hint {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
  align-items: center;
  margin-bottom: $spacing-md;
}

.mw__footer {
  margin-top: $spacing-lg;
  font-size: $font-size-xs;
  line-height: 1.8;
  color: $text-muted;
}

/* ---------------- 选择条 ---------------- */

.picker {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-md;
  align-items: flex-end;
  padding: $spacing-md;
  margin-top: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &__meta {
    margin-left: auto;
    padding-bottom: 6px;
    font-size: $font-size-xs;
    color: $text-muted;
  }
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 180px;

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

    &:disabled {
      opacity: 0.6;
    }
  }
}

/* ---------------- 画布 ---------------- */

.canvas {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  padding: $spacing-md;

  &__head {
    display: flex;
    gap: $spacing-sm;
    align-items: baseline;
  }

  &__title {
    margin: 0;
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__hint {
    margin-left: auto;
    font-size: $font-size-xs;
    color: $text-muted;
  }

  &__stage {
    height: 520px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    overflow: hidden;
  }

  &__foot {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
  }

  &__name {
    width: 170px;
    height: 28px;
    padding: 0 8px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-xs;
    background: $color-surface;

    &:focus {
      outline: none;
      border-color: $color-brand;
    }
  }

  &__version {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
    padding-top: $spacing-sm;
    margin-top: $spacing-sm;
    border-top: 1px dashed $color-border;
  }

  &__version-hint {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__smiles {
    display: flex;
    flex: 1;
    gap: 8px;
    align-items: center;
    min-width: 0;
  }

  &__smiles-label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__smiles-text {
    flex: 1;
    overflow: hidden;
    font-family: monospace;
    font-size: $font-size-xs;
    color: $color-text;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

/* ---------------- 引擎实算 ---------------- */

.engine {
  padding: $spacing-sm $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface-alt;

  &__head {
    display: flex;
    gap: $spacing-xs;
    align-items: center;
    margin-bottom: $spacing-sm;
  }

  &__err {
    margin-left: auto;
    font-size: $font-size-xs;
    color: $error-color;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: $spacing-sm;
  }

  &__cell {
    display: flex;
    flex-direction: column;
    gap: 2px;

    &--wide {
      grid-column: span 2;
    }
  }

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__value {
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    color: $color-text;

    &--mono {
      font-family: monospace;
      font-size: $font-size-xs;
      font-weight: normal;
      word-break: break-all;
    }

    &--delta {
      color: $warning-color;
    }
  }

  &__note {
    margin: $spacing-sm 0 0;
    font-size: $font-size-xs;
    line-height: 1.7;
    color: $text-muted;
  }
}

/* ---------------- 右栏 ---------------- */

.side {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
  min-width: 0;
}

.card {
  padding: $spacing-md;

  &__head {
    display: flex;
    gap: $spacing-sm;
    align-items: center;
    margin-bottom: $spacing-sm;
  }

  &__title {
    margin: 0;
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__head .tag {
    margin-left: auto;
  }

  &__empty {
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.7;
    color: $text-muted;
  }

  &__hint {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-xs;
    line-height: 1.7;
    color: $text-muted;
  }

  &__note {
    margin: $spacing-sm 0 0;
    font-size: $font-size-xs;
    line-height: 1.7;
    color: $color-text-faint;
  }
}

.rules {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
  margin: 0;
  list-style: none;

  &__row {
    display: flex;
    gap: $spacing-sm;
    align-items: center;
    padding: 6px 0;
    border-bottom: 1px dashed $color-border;

    &:last-child {
      border-bottom: 0;
    }
  }

  &__text {
    flex: 1;
    min-width: 0;
    font-size: $font-size-sm;
    color: $color-text;
  }

  &__actual {
    font-family: monospace;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.impacts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
  margin: 0;
  list-style: none;

  &__row {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    padding: 6px 0;
    border-bottom: 1px dashed $color-border;

    &:last-child {
      border-bottom: 0;
    }
  }

  &__dim {
    font-size: $font-size-sm;
    color: $color-text;
    white-space: nowrap;
  }

  &__note {
    flex: 1;
    min-width: 0;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $text-muted;
  }
}

@media (max-width: 1180px) {
  .mw__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .canvas__stage {
    height: 420px;
  }
}
</style>
