<template>
  <div class="dc">
  <Sidebar />
  <main class="dc__main">
    <div class="page">
      <PageHeader
        :title="`${projectName} · 决策`"
        subtitle="装配证据 → 复核否决 → 分桶排名 → 给名额 → 人来拍板"
        icon="⚖️"
      >
        <template #actions>
          <button type="button" class="btn" :disabled="loading" @click="loadAll">
            {{ loading ? '加载中…' : '刷新' }}
          </button>
          <button type="button" class="btn" :disabled="creating" @click="createDecision">
            {{ creating ? '判定中…' : '生成决策' }}
          </button>
          <template v-if="isDraft">
            <button type="button" class="btn" @click="openModal('defer')">转暂缓</button>
            <button type="button" class="btn btn--danger" @click="openModal('cancel')">取消</button>
            <button
              type="button"
              class="btn btn--primary"
              :disabled="!canConfirm"
              :title="canConfirm ? '先重算比对，再落确认' : '没有任何候选进入湿实验，无法确认'"
              @click="openModal('confirm')"
            >
              确认
            </button>
          </template>
        </template>
      </PageHeader>

      <FlowSteps :project-id="projectId" current="decision" />

      <p v-if="error" class="banner banner--danger">{{ error }}</p>
      <p v-if="notice" class="banner banner--ok">{{ notice }}</p>

      <!-- 状态条：先回答「现在能不能看、下一步做什么」 -->
      <section class="status" :class="`status--${statusTone}`">
        <div class="status__main">
          <span class="status__badge">{{ statusLabel }}</span>
          <span class="status__text">{{ statusText }}</span>
        </div>
        <div class="status__meta">
          <SpSelect
            v-if="decisions.length > 1"
            :model-value="currentId"
            size="sm"
            :options="decisionSelectOptions"
            @change="selectDecision(Number($event))"
          />
          <span v-else-if="decision">{{ decision.decisionNo }}</span>
        </div>
      </section>

      <section v-if="!decision && !loading" class="empty">
        <EmptyState
          title="还没有决策记录"
          description="决策只读「已评估冻结」的候选：先冻结基线 → 跑一批生成 → 对候选做评估冻结，再回到这里生成决策。"
        >
          <button type="button" class="btn btn--primary" :disabled="creating" @click="createDecision">
            {{ creating ? '判定中…' : '生成决策' }}
          </button>
        </EmptyState>
      </section>

      <template v-else-if="decision">
        <nav class="tabs">
          <button
            v-for="t in TABS"
            :key="t.key"
            type="button"
            class="tabs__btn"
            :class="{ 'tabs__btn--active': tab === t.key }"
            @click="tab = t.key"
          >
            {{ t.label }}<span v-if="t.count" class="tabs__count">{{ t.count }}</span>
          </button>
        </nav>

        <!-- ============================ 1. 结论总览 ============================ -->
        <section v-if="tab === 'overview'" class="panel">
          <p v-for="(w, i) in decision.warnings" :key="i" class="banner banner--warn">{{ warningText(w) }}</p>

          <div class="caps">
            <div class="cap">
              <span class="cap__label">名额来源</span>
              <span class="cap__value">{{ capacitySource }}</span>
              <span class="cap__hint">档位与湿实验余额取小，且至少 1</span>
            </div>
            <div class="cap cap--strong">
              <span class="cap__label">实际名额</span>
              <span class="cap__value">{{ decision.capacity ?? '—' }}</span>
              <span class="cap__hint">能进湿实验的个数</span>
            </div>
            <div class="cap">
              <span class="cap__label">选中 / 备选</span>
              <span class="cap__value">{{ decision.selectedCount }} / {{ decision.backupCount }}</span>
              <span class="cap__hint">备选不占名额，按名次顺延</span>
            </div>
            <div class="cap">
              <span class="cap__label">淘汰 / 挂起</span>
              <span class="cap__value">{{ decision.rejectedCount }} / {{ decision.deferredCount }}</span>
              <span class="cap__hint">淘汰不参与排名</span>
            </div>
          </div>

          <table class="tbl">
            <thead>
              <tr>
                <th class="tbl__no">名次</th>
                <th>分子</th>
                <th>分桶</th>
                <th class="tbl__num">加权分</th>
                <th class="tbl__num">覆盖指标</th>
                <th>结果</th>
                <th>原因</th>
                <th class="tbl__num">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in sortedCandidates" :key="c.candidateId">
                <td class="tbl__no">{{ c.rankNo ?? '—' }}</td>
                <td>
                  <span class="mol">{{ molTitle(c) }}</span>
                  <span class="mol__id">
                    <span>#{{ c.candidateId }}</span>
                    <span v-if="c.mw" class="mono">MW {{ Number(c.mw).toFixed(1) }}</span>
                    <span v-if="c.smiles" class="mono mol__smiles">{{ c.smiles }}</span>
                  </span>
                </td>
                <td>
                  <span class="tag" :class="bucketTone(c.bucket)">{{ labelOf(BUCKET_LABEL, c.bucket) }}</span>
                </td>
                <td class="tbl__num">
                  <span v-if="c.weightedScore !== null && c.weightedScore !== undefined">
                    {{ Number(c.weightedScore).toFixed(2) }}
                  </span>
                  <span v-else class="muted">—</span>
                </td>
                <td class="tbl__num">
                  <span :title="missingText(c)">{{ c.coversMetrics ?? 0 }} 项</span>
                </td>
                <td>
                  <span class="tag" :class="outcomeTone(c.outcome)">{{ labelOf(OUTCOME_LABEL, c.outcome) }}</span>
                  <span v-if="c.manualOverride" class="tag tag--info" title="人工调整过">人工</span>
                </td>
                <td class="note">{{ rejectText(c) }}</td>
                <td class="tbl__num">
                  <button
                    v-if="isDraft"
                    type="button"
                    class="btn btn--sm"
                    :title="overrideHint(c)"
                    @click="openOverride(c)"
                  >
                    调整
                  </button>
                </td>
              </tr>
            </tbody>
          </table>

          <p class="foot">
            加权分只代表「有数据的指标」—— 分母不含算不出来的指标（缺数据不补零）。
            本项目权重里只有 {{ scoreableCount }} 项能算出值，所以分数要连覆盖指标数一起看。
          </p>

          <h3 class="sub">分桶的含义</h3>
          <ul class="buckets">
            <li v-for="b in BUCKETS" :key="b" class="bucket">
              <span class="tag" :class="bucketTone(b)">{{ labelOf(BUCKET_LABEL, b) }}</span>
              <span class="bucket__count">{{ countOfBucket(b) }} 个</span>
              <span class="bucket__hint">{{ BUCKET_HINT[b] }}</span>
            </li>
          </ul>
        </section>

        <!-- ============================ 2. 否决明细 ============================ -->
        <section v-else-if="tab === 'vetoes'" class="panel">
          <p class="foot foot--top">
            否决不是「分数不够」，而是某条规则被触发。BLOCK 类不可绕过 —— 只能改数据重来，
            不接受在决策层把它划掉。WARN 类可以接受，但需要留痕。
          </p>

          <div v-for="c in vetoCandidates" :key="c.candidateId" class="veto">
            <header class="veto__head">
              <span class="mol">{{ molTitle(c) }}</span>
              <span class="mono mol__smiles mol__smiles--inline">{{ c.smiles || `#${c.candidateId}` }}</span>
              <span class="tag" :class="bucketTone(c.bucket)">{{ labelOf(BUCKET_LABEL, c.bucket) }}</span>
              <span class="tag" :class="outcomeTone(c.outcome)">{{ labelOf(OUTCOME_LABEL, c.outcome) }}</span>
              <span class="veto__reason">{{ rejectText(c) }}</span>
            </header>

            <ul v-if="c.vetoes.length" class="hits">
              <li v-for="(h, i) in c.vetoes" :key="i" class="hit">
                <span class="tag" :class="h.severity === 'BLOCK' ? 'tag--danger' : 'tag--warning'">
                  {{ labelOf(SEVERITY_LABEL, h.severity) }}
                </span>
                <span class="hit__rule">{{ labelOf(VETO_RULE_LABEL, h.ruleCode) }}</span>
                <code class="hit__code">{{ h.ruleCode }}</code>
                <p class="hit__msg">{{ h.message }}</p>
                <p v-if="h.evidenceRef" class="hit__ref">
                  证据位置：<code>{{ h.evidenceRef }}</code>
                </p>
              </li>
            </ul>
            <p v-else class="veto__none">
              没有否决命中 —— 淘汰来自 D2 硬约束复核（改数据或换分子，不是补证据）。
            </p>

            <div v-if="(c.risks?.length ?? 0) > 0" class="risks">
              <p class="risks__title">
                风险点（逐条接受，必须验证项不接受 —— 它只能靠补实验消解）
              </p>
              <ul class="risks__list">
                <li v-for="r in c.risks" :key="r.riskCode" class="risk">
                  <code class="risk__code">{{ r.riskCode }}</code>
                  <span class="tag" :class="severityTone(r.severity)">{{ r.severity || '—' }}</span>
                  <span v-if="r.mustVerify" class="tag tag--danger">必须验证</span>
                  <span v-if="acceptedOf(c, r.riskCode)" class="tag tag--ok">
                    已接受：{{ acceptedOf(c, r.riskCode)?.reason }}
                  </span>
                  <button
                    v-else-if="isDraft && !r.mustVerify"
                    type="button"
                    class="btn btn--sm"
                    @click="openAccept(c, r)"
                  >
                    接受风险
                  </button>
                  <span v-else-if="isDraft" class="risk__hint">需补实验数据，不能接受</span>
                </li>
              </ul>
            </div>
          </div>

          <p v-if="!vetoCandidates.length" class="muted">本次决策没有任何候选被否决。</p>
        </section>

        <!-- ============================ 3. 回放指纹 ============================ -->
        <section v-else-if="tab === 'replay'" class="panel">
          <p class="foot foot--top">
            这三件合起来回答一个问题：<strong>换个人、换个时间，用同样的输入重算，会不会得到同样的结论</strong>。
            任一不同，说明输入或规则变了，结论不可直接沿用。
          </p>

          <div class="fp">
            <div class="fp__item">
              <span class="fp__label">规则版本</span>
              <code class="fp__value">{{ decision.ruleVersion }}</code>
              <span class="fp__hint">参与判定的规则定义的哈希</span>
            </div>
            <div class="fp__item">
              <span class="fp__label">输入快照聚合</span>
              <code class="fp__value">{{ decision.inputSnapshotHash }}</code>
              <span class="fp__hint">全部证据包 hash 的排序聚合</span>
            </div>
            <div class="fp__item">
              <span class="fp__label">权重快照</span>
              <span class="fp__value">{{ weightEntries.length }} 项指标</span>
              <span class="fp__hint">排序用的权重，冻结在记录里</span>
            </div>
          </div>

          <div class="replay">
            <button type="button" class="btn" :disabled="replaying" @click="runReplay">
              {{ replaying ? '重算中…' : '重放比对' }}
            </button>
            <span class="replay__hint">
              用当前输入与当前规则重算一遍，逐候选比对桶/名次/去向。只读，不改任何状态
            </span>
          </div>

          <div
            v-if="replayResult"
            class="replay__result"
            :class="replayResult.same ? 'replay__result--same' : 'replay__result--diff'"
          >
            <strong>{{ replayResult.same ? '一致：同输入 → 同结论' : '不一致：结论不能沿用' }}</strong>
            <span>
              记录 {{ replayResult.storedInputHash.slice(0, 8) }} ·
              重算 {{ replayResult.recomputedInputHash.slice(0, 8) }}
            </span>
            <p v-if="!replayResult.same" class="replay__note">
              输入或规则在两次之间变过，以最新一次为准；确认会被拒绝（409 REPLAY_MISMATCH）。
            </p>
          </div>

          <p v-for="(w, i) in replayResult?.warnings || []" :key="i" class="banner banner--warn">{{ w }}</p>

          <template v-if="replayResult && !replayResult.same">
            <h3 class="sub">逐候选差异</h3>
            <table v-if="replayResult.diffs.length" class="tbl">
              <thead>
                <tr>
                  <th>分子</th>
                  <th>记录</th>
                  <th>重算</th>
                  <th>变化</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in replayResult.diffs" :key="d.candidateId">
                  <td>
                    <span class="mol">{{ d.moleculeName || `候选 #${d.candidateId}` }}</span>
                  </td>
                  <td class="note">
                    {{ labelOf(BUCKET_LABEL, d.storedBucket) }} / 名次 {{ d.storedRankNo ?? '—' }} /
                    {{ labelOf(OUTCOME_LABEL, d.storedOutcome) }}
                  </td>
                  <td class="note">
                    {{ labelOf(BUCKET_LABEL, d.currentBucket) }} / 名次 {{ d.currentRankNo ?? '—' }} /
                    {{ labelOf(OUTCOME_LABEL, d.currentOutcome) }}
                  </td>
                  <td class="note">{{ d.changes.join('；') }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="muted">候选集合与名次都一样，差异只来自证据内容（指纹变了但排序未变）。</p>

            <p v-if="replayResult.addedCandidates.length" class="note">
              本次新参与：{{ replayResult.addedCandidates.map((id) => `#${id}`).join('、') }}
            </p>
            <p v-if="replayResult.removedCandidates.length" class="note">
              本次不再参与：{{ replayResult.removedCandidates.map((id) => `#${id}`).join('、') }}
            </p>
          </template>

          <h3 class="sub">排序权重</h3>
          <table class="tbl">
            <thead>
              <tr>
                <th>指标</th>
                <th>代码</th>
                <th class="tbl__num">权重</th>
                <th>能否算出</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="w in weightEntries" :key="w.code">
                <td>{{ w.name }}</td>
                <td><code class="mono">{{ w.code }}</code></td>
                <td class="tbl__num">{{ w.weight }}</td>
                <td>
                  <span v-if="w.missing" class="tag tag--warning">缺数据</span>
                  <span v-else class="tag tag--ok">有数据</span>
                </td>
              </tr>
            </tbody>
          </table>
        </section>

        <!-- ============================ 4. 否决规则 ============================ -->
        <section v-else class="panel">
          <p class="foot foot--top">
            全局规则随系统一起发布，<strong>只读</strong>；本项目的规则可以增删改。
            每条规则都必须有「用户可读的判定说明」—— 不允许存在用户看不见的规则。
          </p>

          <table class="tbl">
            <thead>
              <tr>
                <th>规则</th>
                <th>严重度</th>
                <th>判定说明</th>
                <th>阈值 / 参数</th>
                <th class="tbl__num">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rules" :key="r.id" :class="{ 'tbl__row--off': !r.enabled }">
                <td>
                  <span class="mol">{{ r.humanReadable }}</span>
                  <span class="mol__id">
                    <code>{{ r.ruleCode }}</code>
                    <span v-if="r.global" class="tag tag--neutral">全局</span>
                    <span v-else class="tag tag--info">本项目</span>
                    <span v-if="!r.enabled" class="tag tag--neutral">已停用</span>
                  </span>
                </td>
                <td>
                  <span class="tag" :class="r.severity === 'BLOCK' ? 'tag--danger' : 'tag--warning'">
                    {{ labelOf(SEVERITY_LABEL, r.severity) }}
                  </span>
                </td>
                <td class="note">{{ VETO_RULE_LABEL[r.ruleCode] || '—' }}</td>
                <td class="note">
                  <code v-if="r.thresholdNum !== null && r.thresholdNum !== undefined">{{ r.thresholdNum }}</code>
                  <code v-if="r.paramsJson">{{ r.paramsJson }}</code>
                  <span v-if="!r.thresholdNum && !r.paramsJson" class="muted">—</span>
                </td>
                <td class="tbl__num">
                  <button
                    v-if="!r.global"
                    type="button"
                    class="btn btn--sm"
                    @click="toggleRule(r)"
                  >
                    {{ r.enabled ? '停用' : '启用' }}
                  </button>
                  <button v-if="!r.global" type="button" class="btn btn--sm btn--danger" @click="removeRule(r)">
                    删除
                  </button>
                  <span v-if="r.global" class="muted">只读</span>
                </td>
              </tr>
            </tbody>
          </table>

          <h3 class="sub">新增本项目规则</h3>
          <div class="form">
            <label class="field">
              <span class="field__label">规则类型</span>
              <SpSelect
                v-model="ruleForm.ruleCode"
                block
                :options="ruleCodeOptions"
              />
            </label>
            <label class="field">
              <span class="field__label">严重度</span>
              <SpSelect
                v-model="ruleForm.severity"
                block
                :options="RULE_SEVERITY_OPTIONS"
              />
            </label>
            <label class="field field--wide">
              <span class="field__label">判定说明 *</span>
              <input
                v-model.trim="ruleForm.humanReadable"
                type="text"
                maxlength="500"
                class="field__control"
                placeholder="例：自由实施风险分值超过 0.85 的分子不得进入湿实验"
              />
            </label>
            <label class="field">
              <span class="field__label">阈值（可选）</span>
              <input v-model.number="ruleForm.thresholdNum" type="number" step="0.01" class="field__control" />
            </label>
            <div class="form__actions">
              <button
                type="button"
                class="btn btn--primary"
                :disabled="savingRule || !ruleForm.humanReadable"
                @click="addRule"
              >
                {{ savingRule ? '保存中…' : '+ 新增规则' }}
              </button>
              <span class="form__tip">停用与删除只对本项目规则生效；全局规则需要随系统发布</span>
            </div>
          </div>
        </section>
      </template>
    </div>
  </main>

  <!-- 统一的操作弹窗：调整 / 接受风险 / 确认 / 暂缓 / 取消 -->
  <div v-if="modal" class="modal" @click.self="closeModal">
    <div class="modal__box">
      <header class="modal__head">
        <h3 class="modal__title">{{ MODAL_TITLE[modal.kind] }}</h3>
        <button type="button" class="modal__close" @click="closeModal">×</button>
      </header>

      <p class="modal__desc">{{ MODAL_DESC[modal.kind] }}</p>

      <!-- 调整去向 -->
      <template v-if="modal.kind === 'override'">
        <p class="modal__subject">
          {{ modal.candidate?.moleculeName || `候选 #${modal.candidate?.candidateId}` }}
          <span class="note">
            当前 {{ labelOf(OUTCOME_LABEL, modal.candidate?.outcome) }} ·
            {{ labelOf(BUCKET_LABEL, modal.candidate?.bucket) }}
          </span>
        </p>
        <label class="field">
          <span class="field__label">调整成 *</span>
          <SpSelect
            v-model="form.outcome"
            block
            :options="outcomeOptionsForCandidate"
          />
        </label>
      </template>

      <!-- 接受风险 -->
      <template v-else-if="modal.kind === 'accept'">
        <p class="modal__subject">
          {{ modal.candidate?.moleculeName || `候选 #${modal.candidate?.candidateId}` }}
          · 风险 <code>{{ modal.risk?.riskCode }}</code>
          <span class="tag" :class="severityTone(modal.risk?.severity)">{{ modal.risk?.severity || '—' }}</span>
        </p>
      </template>

      <!-- 确认：先展示重算结果，再让人点「确认」 -->
      <template v-else-if="modal.kind === 'confirm'">
        <p v-if="confirmPreview === null" class="modal__desc">正在重算比对…</p>
        <template v-else>
          <p class="modal__desc" :class="confirmPreview.same ? 'ok' : 'bad'">
            {{ confirmPreview.same
              ? '重算一致：同输入 → 同结论，可以确认。'
              : '重算不一致：输入或规则变了，确认会被拒绝。请先处理差异或新建一版。' }}
          </p>
          <p v-if="decision" class="modal__subject">
            将确认：选中 {{ decision.selectedCount }} 个（名额 {{ decision.capacity ?? '—' }}），
            备选 {{ decision.backupCount }} 个；确认后不可再改。
          </p>
        </template>
      </template>

      <!-- 暂缓 / 取消 只是要一个理由 -->
      <label class="field">
        <span class="field__label">
          {{ modal.kind === 'confirm' ? '备注（可选）' : '理由 *' }}
        </span>
        <textarea
          v-model.trim="form.reason"
          rows="3"
          maxlength="500"
          class="field__control field__control--area"
          :placeholder="MODAL_PLACEHOLDER[modal.kind]"
        />
      </label>

      <p v-if="modal.error" class="banner banner--danger">{{ modal.error }}</p>

      <footer class="modal__foot">
        <button type="button" class="btn" @click="closeModal">取消</button>
        <button
          type="button"
          class="btn btn--primary"
          :disabled="modal.busy || !modalValid"
          @click="submitModal"
        >
          {{ modal.busy ? '处理中…' : MODAL_TITLE[modal.kind] }}
        </button>
      </footer>
    </div>
  </div>
</div>
</template>

<script setup lang="ts">
/**
 * 决策（阶段 E）页面 —— 五段式：结论总览 / 否决明细 / 回放指纹 / 否决规则。
 *
 * 为什么不做成「一张大表 + 一堆 JSON」：
 * 决策的产出是「拿哪几个去做湿实验」，用户真正要回答的是三个问题 ——
 *   ① 谁进了、谁没进、凭什么（结论总览 + 否决明细）
 *   ② 这个结论能不能被复查、能不能复用（回放指纹）
 *   ③ 规则是谁定的、我能不能改（否决规则）
 * 三件事混在一屏里必然看不懂，所以拆成页签，页签上直接标数量。
 *
 * 界面上刻意保留两个「不好听但必须说」的事实：
 *   · 加权分只代表有数据的指标（本项目覆盖率约三分之一），所以分数旁边永远跟着覆盖指标数；
 *   · 淘汰原因分「该换分子」与「该补数据」两类，两者下一步动作相反。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SpSelect from '@/components/ui/SpSelect.vue'
import FlowSteps from '@/components/design/FlowSteps.vue'
import { decisionApi, decisionVetoRuleApi } from '@/api/decision'
import type {
  Decision,
  DecisionCandidate,
  DecisionReplay,
  DecisionVetoRule,
  DecisionVetoRuleRequest,
  RiskFlag
} from '@/api/decision'
import { requirementApi } from '@/api/requirement'
import type { ProjectBrief, ProjectMetric } from '@/api/requirement'
import {
  BUCKET_HINT,
  BUCKET_LABEL,
  DECISION_STATUS_LABEL,
  FILTER_REASON_LABEL,
  OUTCOME_LABEL,
  SEVERITY_LABEL,
  VETO_RULE_LABEL,
  labelOf
} from '@/utils/designLabels'
import type { ApiError } from '@/utils/request'

const route = useRoute()
const projectId = Number(route.params.projectId)

const loading = ref(false)
const creating = ref(false)
const error = ref('')
const notice = ref('')

const project = ref<ProjectBrief | null>(null)
const metrics = ref<ProjectMetric[]>([])
const decisions = ref<Decision[]>([])
const decision = ref<Decision | null>(null)
const currentId = ref(0)
const rules = ref<DecisionVetoRule[]>([])

/** 决策轮次切换下拉 */
const decisionSelectOptions = computed(() =>
  decisions.value.map((d) => ({
    value: d.id,
    label: `${d.decisionNo} · ${labelOf(DECISION_STATUS_LABEL, d.status)}`
  }))
)

const tab = ref<'overview' | 'vetoes' | 'replay' | 'rules'>('overview')

const BUCKETS = ['FULL_PASS', 'TOLERANCE', 'FAIL'] as const

const projectName = computed(() => project.value?.name || `项目 #${projectId}`)

// ---------------------------------------------------------------- 拉取

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [p, m] = await Promise.all([
      requirementApi.getProject(projectId).catch(() => null),
      requirementApi.listMetrics(projectId).catch(() => [] as ProjectMetric[])
    ])
    project.value = p
    metrics.value = m

    const list = await decisionApi.listByProject(projectId)
    decisions.value = list ?? []
    if (decisions.value.length) {
      const keep = decisions.value.find((d) => d.id === currentId.value) || decisions.value[0]
      currentId.value = keep.id
      decision.value = await decisionApi.get(keep.id)
    } else {
      currentId.value = 0
      decision.value = null
    }
    await loadRules()
  } catch (e) {
    error.value = (e as ApiError).message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadRules() {
  try {
    rules.value = (await decisionVetoRuleApi.list(projectId)) ?? []
  } catch {
    // 规则读不到不该拖垮结论展示：结论本身不依赖这个面板
    rules.value = []
  }
}

async function selectDecision(id: number) {
  if (!id || id === currentId.value) return
  currentId.value = id
  error.value = ''
  replayResult.value = null
  try {
    decision.value = await decisionApi.get(id)
  } catch (e) {
    error.value = (e as ApiError).message || '加载决策失败'
  }
}

/** 把写操作后的最新决策放回当前视图与历史列表 */
function applyDecision(d: Decision) {
  decision.value = d
  currentId.value = d.id
  const idx = decisions.value.findIndex((x) => x.id === d.id)
  if (idx >= 0) decisions.value[idx] = d
  else decisions.value = [d, ...decisions.value]
  replayResult.value = null
  confirmPreview.value = null
}

async function createDecision() {
  creating.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await decisionApi.create(projectId, {})
    applyDecision(created)
    notice.value =
      `已生成决策 ${created.decisionNo}：名额 ${created.capacity ?? '—'}，` +
      `选中 ${created.selectedCount}，备选 ${created.backupCount}，淘汰 ${created.rejectedCount}`
  } catch (e) {
    error.value = (e as ApiError).message || '生成决策失败'
  } finally {
    creating.value = false
  }
}

// ---------------------------------------------------------------- 展示

const TABS = computed(() => [
  { key: 'overview' as const, label: '结论总览', count: decision.value?.candidates.length || 0 },
  { key: 'vetoes' as const, label: '否决明细', count: vetoCandidates.value.length },
  { key: 'replay' as const, label: '回放指纹', count: 0 },
  { key: 'rules' as const, label: '否决规则', count: rules.value.length }
])

const statusLabel = computed(() => {
  if (loading.value) return '加载中'
  if (!decision.value) return '待决策'
  return labelOf(DECISION_STATUS_LABEL, decision.value.status)
})

/** 只有草稿能改：确认即不可改 */
const isDraft = computed(() => decision.value?.status === 'DRAFT')
/** 没有候选入选就没有可执行的东西，确认按钮置灰（后端也会 409） */
const canConfirm = computed(() => isDraft.value && (decision.value?.selectedCount ?? 0) > 0)

const statusText = computed(() => {
  const d = decision.value
  if (!d) return '还没有决策记录 —— 先冻结基线、生成候选并逐个评估冻结，再回到这里'
  const warnings = d.warnings || []
  if (d.status === 'CONFIRMED') {
    return `已确认：选出 ${d.selectedCount} 个进入湿实验` +
      (d.decidedAt ? `（${d.decidedAt.replace('T', ' ').slice(0, 16)}）` : '') +
      '；确认后不可再改，要改请新建一版'
  }
  if (d.status === 'DEFERRED') return '已转暂缓：补完数据后可重新生成决策'
  if (d.status === 'CANCELLED') return '已取消：这条决策作废，不会进入湿实验'
  if (d.selectedCount > 0) {
    return `已选出 ${d.selectedCount} 个进入湿实验、${d.backupCount} 个备选；可调整、接受风险，然后确认`
  }
  if (warnings.some((w) => w.startsWith('NO_VIABLE_CANDIDATE')) ||
      (d.selectedCount === 0 && d.rejectedCount > 0)) {
    return '本次没有任何候选能进湿实验 —— 看「否决明细」定位是哪条规则拦住的'
  }
  return '本次没有选中任何候选，请查看淘汰原因'
})

const statusTone = computed(() => {
  if (error.value) return 'danger'
  if (!decision.value) return 'todo'
  if (decision.value.status === 'CONFIRMED') return 'ok'
  if (decision.value.status === 'DEFERRED' || decision.value.status === 'CANCELLED') return 'warn'
  if (decision.value.selectedCount > 0) return 'ok'
  return 'warn'
})

/** 排序：有名次的按名次在前，FAIL 桶按候选 id 稳序在后 */
const sortedCandidates = computed<DecisionCandidate[]>(() => {
  const list = [...(decision.value?.candidates ?? [])]
  return list.sort((a, b) => {
    const ra = a.rankNo ?? Number.MAX_SAFE_INTEGER
    const rb = b.rankNo ?? Number.MAX_SAFE_INTEGER
    if (ra !== rb) return ra - rb
    return a.candidateId - b.candidateId
  })
})

/** 有否决命中的候选；桶是 FAIL 但没有命中的也要列出来（那是硬约束复核失败） */
const vetoCandidates = computed(() =>
  sortedCandidates.value.filter((c) => c.vetoes.length > 0 || c.bucket === 'FAIL' || c.rejectReason)
)

function countOfBucket(bucket: string) {
  return (decision.value?.candidates ?? []).filter((c) => c.bucket === bucket).length
}

const capacitySource = computed(() => {
  const p = project.value
  if (!p) return '—'
  const mode = p.decisionCapacityMode
  const value = p.decisionCapacityValue
  if (!mode || value === null || value === undefined) return '未配置（取湿实验余额）'
  return mode === 'RATIO' ? `候选数 × ${value}` : `固定 ${value} 个`
})

/** 权重快照里能算出值的指标数 —— 加权分的分母 */
const scoreableCount = computed(() => {
  const first = sortedCandidates.value.find((c) => c.coversMetrics !== null && c.coversMetrics !== undefined)
  return first?.coversMetrics ?? 0
})

const weightEntries = computed(() => {
  const snap = decision.value?.weightsSnapshot ?? {}
  const missing = new Set<string>()
  for (const c of decision.value?.candidates ?? []) {
    for (const code of c.missingMetrics ?? []) missing.add(code)
  }
  return Object.keys(snap).map((code) => ({
    code,
    name: metricName(code),
    weight: formatWeight(snap[code]),
    missing: missing.has(code)
  }))
})

function metricName(code: string) {
  const hit = metrics.value.find((m) => m.metricCode === code)
  return hit?.metricName || code
}

function formatWeight(v: unknown): string {
  if (typeof v === 'number') return String(v)
  if (typeof v === 'string') return v
  if (v === null || v === undefined) return '—'
  return JSON.stringify(v)
}

function missingText(c: DecisionCandidate) {
  if (!c.missingMetrics?.length) return '权重里所有指标都算出了值'
  return `缺数据：${c.missingMetrics.map(metricName).join('、')}`
}

/** 分子标题：有名字用名字，没有就退回「候选 #id（SMILES）」——生成阶段的分子通常没名字 */
function molTitle(c: DecisionCandidate) {
  if (c.moleculeName) return c.moleculeName
  return `候选 #${c.candidateId}`
}

function rejectText(c: DecisionCandidate) {
  if (!c.rejectReason) return c.outcome === 'SELECTED' ? '按名次进入名额' : '—'
  return FILTER_REASON_LABEL[c.rejectReason] || c.rejectReason
}

function warningText(w: string) {
  if (w === 'NO_VIABLE_CANDIDATE') return '没有任何候选能进湿实验：所有候选都被否决或硬约束复核不达标'
  return w
}

function bucketTone(bucket: string) {
  if (bucket === 'FULL_PASS') return 'tag--ok'
  if (bucket === 'TOLERANCE') return 'tag--warning'
  return 'tag--danger'
}

function outcomeTone(outcome: string) {
  if (outcome === 'SELECTED') return 'tag--ok'
  if (outcome === 'BACKUP') return 'tag--info'
  if (outcome === 'DEFERRED') return 'tag--warning'
  return 'tag--neutral'
}

// ---------------------------------------------------------------- 回放

const replaying = ref(false)
const replayResult = ref<DecisionReplay | null>(null)

/**
 * 重放比对：调后端 `:replay`，用当前输入与当前规则重算一遍。
 * 不在前端复算 —— 前端复算等于用另一套代码给自己作证，什么也证明不了。
 * 也不新建决策 —— 「只看会不会得到同一份名单」这件事不该留下副作用。
 */
async function runReplay() {
  const d = decision.value
  if (!d) return
  replaying.value = true
  replayResult.value = null
  error.value = ''
  try {
    replayResult.value = await decisionApi.replay(d.id)
  } catch (e) {
    error.value = (e as ApiError).message || '重放失败'
  } finally {
    replaying.value = false
  }
}

// ---------------------------------------------------------------- 操作弹窗

type ModalKind = 'override' | 'accept' | 'confirm' | 'defer' | 'cancel'

interface ModalState {
  kind: ModalKind
  candidate?: DecisionCandidate | null
  risk?: RiskFlag | null
  busy: boolean
  error: string
}

const modal = ref<ModalState | null>(null)
const confirmPreview = ref<DecisionReplay | null>(null)
const form = ref({ outcome: 'SELECTED' as string, reason: '' })

const MODAL_TITLE: Record<ModalKind, string> = {
  override: '人工调整去向',
  accept: '接受风险',
  confirm: '确认决策',
  defer: '转暂缓',
  cancel: '取消决策'
}

const MODAL_DESC: Record<ModalKind, string> = {
  override: '调整会记下理由并标记为人工调整。被 BLOCK 否决或硬约束不通过的候选改不了入选 —— 那只能改数据重来。',
  accept: '接受的是「这个风险我认了」，所以要写清为什么。必须验证的风险项不接受，只能补实验。',
  confirm: '确认后不可再改；要改请新建一版。确认前会先用当前输入重算一遍，避免拿旧结论下单。',
  defer: '转暂缓表示「这次不算数，补数据后重开」。理由会写进决策备注。',
  cancel: '取消后这条决策作废，不会进入湿实验。理由会写进决策备注。'
}

const MODAL_PLACEHOLDER: Record<ModalKind, string> = {
  override: '例：备选与首选同分，两个都要做',
  accept: '例：该风险可在临床前阶段观察，先接受',
  confirm: '可选的备注，例如「本轮先做这 2 个」',
  defer: '例：湿实验额度待批，下周再定',
  cancel: '例：方向调整，本批不再推进'
}

const OUTCOME_OPTIONS = ['SELECTED', 'BACKUP', 'DEFERRED', 'REJECTED']

/**
 * 调整去向的选项。不允许的去向仍然列出来但禁用并说明原因 ——
 * 直接隐藏会让用户以为「系统没有这个功能」，而禁用能讲清「为什么不行」。
 */
const outcomeOptionsForCandidate = computed(() =>
  OUTCOME_OPTIONS.map((o) => ({
    value: o,
    label: overridableTo(o)
      ? labelOf(OUTCOME_LABEL, o)
      : `${labelOf(OUTCOME_LABEL, o)}（该候选不允许）`,
    disabled: !overridableTo(o)
  }))
)

function openModal(kind: ModalKind) {
  modal.value = { kind, busy: false, error: '' }
  form.value = { outcome: 'SELECTED', reason: '' }
  confirmPreview.value = null
  if (kind === 'confirm') void loadConfirmPreview()
}

function openOverride(c: DecisionCandidate) {
  modal.value = { kind: 'override', candidate: c, busy: false, error: '' }
  form.value = {
    outcome: c.bucket === 'FAIL' ? 'REJECTED' : c.outcome === 'SELECTED' ? 'BACKUP' : 'SELECTED',
    reason: ''
  }
}

function openAccept(c: DecisionCandidate, r: RiskFlag) {
  modal.value = { kind: 'accept', candidate: c, risk: r, busy: false, error: '' }
  form.value = { outcome: 'SELECTED', reason: '' }
}

function closeModal() {
  modal.value = null
  confirmPreview.value = null
}

async function loadConfirmPreview() {
  const d = decision.value
  if (!d) return
  try {
    confirmPreview.value = await decisionApi.replay(d.id)
  } catch (e) {
    confirmPreview.value = null
    if (modal.value) modal.value.error = (e as ApiError).message || '重算失败'
  }
}

const modalValid = computed(() => {
  const m = modal.value
  if (!m || m.busy) return false
  if (m.kind === 'confirm') return confirmPreview.value?.same === true
  return form.value.reason.trim().length > 0
})

async function submitModal() {
  const m = modal.value
  const d = decision.value
  if (!m || !d) return
  m.busy = true
  m.error = ''
  error.value = ''
  notice.value = ''
  try {
    let updated: Decision | null = null
    if (m.kind === 'override' && m.candidate) {
      updated = await decisionApi.override(d.id, m.candidate.candidateId, form.value.outcome, form.value.reason)
      notice.value = `候选 #${m.candidate.candidateId} 已调整为「${labelOf(OUTCOME_LABEL, form.value.outcome)}」`
    } else if (m.kind === 'accept' && m.candidate && m.risk) {
      updated = await decisionApi.acceptRisk(d.id, m.candidate.candidateId, m.risk.riskCode, form.value.reason)
      notice.value = `已接受候选 #${m.candidate.candidateId} 的风险 ${m.risk.riskCode}`
    } else if (m.kind === 'confirm') {
      updated = await decisionApi.confirm(d.id, form.value.reason || undefined)
      notice.value = `决策 ${updated.decisionNo} 已确认（选中 ${updated.selectedCount} 个）`
    } else if (m.kind === 'defer') {
      updated = await decisionApi.defer(d.id, form.value.reason)
      notice.value = '已转暂缓；补完数据可重新生成决策'
    } else if (m.kind === 'cancel') {
      updated = await decisionApi.cancel(d.id, form.value.reason)
      notice.value = '决策已取消'
    }
    if (updated) applyDecision(updated)
    closeModal()
    await loadAll()
  } catch (e) {
    const msg = (e as ApiError).message || '操作失败'
    if (modal.value) modal.value.error = msg
    error.value = msg
  } finally {
    if (modal.value) modal.value.busy = false
  }
}

// ---------------------------------------------------------------- 人工调整的边界（与后端同一口径）

/** FAIL 桶或被 BLOCK 否决 → 只能往更严的方向调 */
function overridableTo(outcome: string) {
  const c = modal.value?.candidate
  if (!c) return true
  if (outcome === 'REJECTED' || outcome === 'DEFERRED') return true
  if (c.bucket === 'FAIL') return false
  return !(c.vetoes || []).some((v) => v.severity === 'BLOCK')
}

function overrideHint(c: DecisionCandidate) {
  if (!isDraft.value) return '只有草稿可以调整'
  if (c.bucket === 'FAIL' || (c.vetoes || []).some((v) => v.severity === 'BLOCK')) {
    return '被否决或复核不通过：只能改为淘汰/暂缓，不能入选'
  }
  return '人工调整去向（必填理由）'
}

function acceptedOf(c: DecisionCandidate, riskCode: string) {
  return (c.acceptances || []).find((a) => a.riskCode === riskCode)
}

function severityTone(severity?: string | null) {
  if (!severity) return 'tag--neutral'
  if (severity === 'HIGH' || severity === 'BLOCK') return 'tag--danger'
  if (severity === 'MEDIUM' || severity === 'WARN') return 'tag--warning'
  return 'tag--neutral'
}

onMounted(loadAll)

// ---------------------------------------------------------------- 规则维护

const savingRule = ref(false)
const ruleForm = ref<{ ruleCode: string; severity: string; humanReadable: string; thresholdNum: number | null }>({
  ruleCode: 'FTO_RISK_HIGH',
  severity: 'BLOCK',
  humanReadable: '',
  thresholdNum: null
})

const ruleCodeOptions = computed(() =>
  Object.entries(VETO_RULE_LABEL).map(([value, label]) => ({ value, label }))
)

const RULE_SEVERITY_OPTIONS = [
  { value: 'BLOCK', label: '一票否决（BLOCK）' },
  { value: 'WARN', label: '告警（WARN）' }
]

function toRuleBody(r: DecisionVetoRule): DecisionVetoRuleRequest {
  return {
    ruleCode: r.ruleCode,
    severity: r.severity,
    dimension: r.dimension || undefined,
    thresholdNum: r.thresholdNum ?? null,
    paramsJson: r.paramsJson || undefined,
    humanReadable: r.humanReadable,
    enabled: r.enabled,
    sortNo: r.sortNo ?? null
  }
}

async function toggleRule(r: DecisionVetoRule) {
  error.value = ''
  try {
    await decisionVetoRuleApi.update(r.id, { ...toRuleBody(r), enabled: !r.enabled })
    await loadRules()
  } catch (e) {
    error.value = (e as ApiError).message || '更新规则失败'
  }
}

async function removeRule(r: DecisionVetoRule) {
  error.value = ''
  notice.value = ''
  try {
    await decisionVetoRuleApi.remove(r.id)
    notice.value = '规则已删除'
    await loadRules()
  } catch (e) {
    error.value = (e as ApiError).message || '删除规则失败'
  }
}

async function addRule() {
  savingRule.value = true
  error.value = ''
  notice.value = ''
  try {
    await decisionVetoRuleApi.create(projectId, {
      ruleCode: ruleForm.value.ruleCode,
      severity: ruleForm.value.severity,
      humanReadable: ruleForm.value.humanReadable,
      thresholdNum: ruleForm.value.thresholdNum
    })
    ruleForm.value.humanReadable = ''
    ruleForm.value.thresholdNum = null
    notice.value = '规则已新增；下次生成决策时生效'
    await loadRules()
  } catch (e) {
    error.value = (e as ApiError).message || '新增规则失败'
  } finally {
    savingRule.value = false
  }
}

onMounted(loadAll)
</script>

<style lang="scss" scoped>
.dc {
  &__main {
    min-height: 100vh;
    padding: 0 $spacing-xl $spacing-2xl;
    background: $bg-secondary;
  }
}

.empty {
  padding: $spacing-2xl 0;
}

/* ---------------------------------------------------------- 状态条 */

.status {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  margin-bottom: $spacing-lg;
  border: 1px solid $color-border;
  border-left: 3px solid $text-muted;
  border-radius: $radius-card;
  background: $color-surface;

  &--ok {
    border-left-color: $success-color;
    background: rgba($success-color, 0.06);
  }

  &--warn {
    border-left-color: $warning-color;
    background: rgba($warning-color, 0.08);
  }

  &--danger {
    border-left-color: $error-color;
    background: rgba($error-color, 0.06);
  }

  &__main {
    display: flex;
    gap: $spacing-sm;
    align-items: center;
  }

  &__badge {
    padding: 2px 10px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    font-weight: $font-weight-semibold;
    color: #fff;
    background: $color-brand;
  }

  &__text {
    font-size: $font-size-sm;
    color: $color-text;
  }

  &__meta {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__select {
    padding: 4px 8px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-xs;
    background: $color-surface;
  }
}

/* ---------------------------------------------------------- 页签 */

.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: $spacing-md;
  border-bottom: 1px solid $color-border;

  &__btn {
    display: flex;
    gap: 6px;
    align-items: center;
    padding: 8px 14px;
    margin-bottom: -1px;
    border: none;
    border-bottom: 2px solid transparent;
    font-size: $font-size-sm;
    color: $color-text-faint;
    background: transparent;
    cursor: pointer;

    &--active {
      color: $color-brand;
      border-bottom-color: $color-brand;
      font-weight: $font-weight-semibold;
    }
  }

  &__count {
    padding: 0 6px;
    border-radius: $radius-pill;
    font-size: 10px;
    color: $color-text-faint;
    background: $color-surface-sunken;
  }
}

.panel {
  padding-bottom: $spacing-2xl;
}

/* ---------------------------------------------------------- 名额卡 */

.caps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: $spacing-sm;
  margin-bottom: $spacing-lg;
}

.cap {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &--strong {
    border-color: $color-brand;

    .cap__value {
      color: $color-brand;
    }
  }

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__value {
    font-size: 18px;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__hint {
    font-size: $font-size-xs;
    color: $text-light;
  }
}

/* ---------------------------------------------------------- 表格 */

.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: $font-size-sm;

  th,
  td {
    padding: 8px 10px;
    text-align: left;
    border-bottom: 1px solid $color-border;
    vertical-align: top;
  }

  th {
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    color: $color-text-faint;
    background: $color-surface-alt;
  }

  &__no {
    width: 48px;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }

  &__num {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }

  &__row--off {
    opacity: 0.55;
  }
}

.mol {
  font-weight: $font-weight-medium;
  color: $color-text;
  word-break: break-all;

  &__id {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-top: 2px;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__smiles {
    max-width: 260px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: $font-family-mono;
    font-size: 10px;
    color: $text-light;

    &--inline {
      max-width: 200px;
      margin: 0;
    }
  }
}

.note {
  font-size: $font-size-xs;
  color: $color-text-faint;
}

.muted {
  color: $text-light;
  font-size: $font-size-xs;
}

.mono {
  font-family: $font-family-mono;
  font-size: $font-size-xs;
}

.foot {
  margin-top: $spacing-sm;
  font-size: $font-size-xs;
  color: $color-text-faint;

  &--top {
    margin: 0 0 $spacing-md;
  }
}

.sub {
  margin: $spacing-xl 0 $spacing-sm;
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text;
}

/* ---------------------------------------------------------- 分桶 */

.buckets {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.bucket {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
  font-size: $font-size-sm;

  &__count {
    min-width: 44px;
    color: $color-text-faint;
    font-variant-numeric: tabular-nums;
  }

  &__hint {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

/* ---------------------------------------------------------- 否决 */

.veto {
  padding: 12px 14px;
  margin-bottom: $spacing-sm;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  &__reason {
    margin-left: auto;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__none {
    margin: $spacing-sm 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.hits {
  padding: 0;
  margin: $spacing-sm 0 0;
  list-style: none;
}

.hit {
  padding: 8px 10px;
  margin-top: 6px;
  border-radius: $radius-control;
  background: $color-surface-alt;

  &__rule {
    margin-left: 6px;
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
  }

  &__code {
    margin-left: 6px;
    font-family: $font-family-mono;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__msg {
    margin: 4px 0 0;
    font-size: $font-size-sm;
    color: $color-text;
  }

  &__ref {
    margin: 4px 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

/* ---------------------------------------------------------- 回放 */

.fp {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: $spacing-sm;
}

.fp__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  .fp__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  .fp__value {
    font-family: $font-family-mono;
    font-size: $font-size-sm;
    color: $color-text;
    word-break: break-all;
  }

  .fp__hint {
    font-size: $font-size-xs;
    color: $text-light;
  }
}

.replay {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
  margin-top: $spacing-md;

  &__hint {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__result {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: baseline;
    padding: 10px 14px;
    margin-top: $spacing-sm;
    border-radius: $radius-card;
    font-size: $font-size-sm;

    &--same {
      color: $success-color;
      background: rgba($success-color, 0.1);
    }

    &--diff {
      color: $error-color;
      background: rgba($error-color, 0.1);
    }
  }

  &__note {
    width: 100%;
    margin: 0;
    font-size: $font-size-xs;
  }
}

/* ---------------------------------------------------------- 表单 */

.form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: $spacing-sm $spacing-md;
  padding: 14px;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &__actions {
    display: flex;
    gap: $spacing-sm;
    align-items: center;
    grid-column: 1 / -1;
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
    grid-column: 1 / -1;
  }

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__control {
    height: 32px;
    padding: 0 10px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-sm;
    color: $color-text;
    background: $color-surface;

    &:focus {
      outline: none;
      border-color: $color-brand;
    }

    &--area {
      height: auto;
      padding: 8px 10px;
      line-height: 1.5;
      resize: vertical;
      font-family: inherit;
    }
  }
}

/* ---------------------------------------------------------- 风险点 */

.risks {
  margin-top: $spacing-sm;
  padding-top: $spacing-sm;
  border-top: 1px dashed $color-border;

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__list {
    padding: 0;
    margin: 0;
    list-style: none;
  }
}

.risk {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 4px 0;

  &__code {
    font-family: $font-family-mono;
    font-size: $font-size-xs;
    color: $color-text;
  }

  &__hint {
    font-size: $font-size-xs;
    color: $text-light;
  }
}

/* ---------------------------------------------------------- 操作弹窗 */

.modal {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: $spacing-lg;
  background: rgba(15, 43, 44, 0.42);

  &__box {
    width: 100%;
    max-width: 520px;
    max-height: 88vh;
    overflow: auto;
    padding: $spacing-lg;
    border-radius: $radius-card;
    background: $color-surface;
    box-shadow: 0 18px 48px rgba(15, 43, 44, 0.28);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: $spacing-sm;
  }

  &__title {
    margin: 0;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__close {
    border: none;
    font-size: 20px;
    line-height: 1;
    color: $color-text-faint;
    background: transparent;
    cursor: pointer;
  }

  &__desc {
    margin: 0 0 $spacing-md;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;

    &.ok {
      color: $success-color;
    }

    &.bad {
      color: $error-color;
    }
  }

  &__subject {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-sm;
    color: $color-text;
  }

  &__foot {
    display: flex;
    gap: $spacing-sm;
    justify-content: flex-end;
    margin-top: $spacing-md;
  }
}
</style>
