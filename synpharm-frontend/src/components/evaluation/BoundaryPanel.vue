<template>
  <div class="bp">
    <div class="bp__intro">
      评价体系里真正会算分的模型只有三个。写清每个模型吃什么、吐什么、边界在哪，
      比再堆一堆「智能预测」的宣传语有用：用户点下去之前就知道自己的输入会不会被拒。
    </div>

    <EngineStatusBar show-probe-at show-refresh />

    <div class="bp__grid">
      <article v-for="item in BOUNDARIES" :key="item.code" class="bp__card">
        <header class="bp__card-head">
          <span class="bp__badge">{{ item.code }}</span>
          <h3 class="bp__name">{{ item.name }}</h3>
        </header>

        <p class="bp__sub">{{ item.purpose }}</p>

        <dl class="bp__facts">
          <div class="bp__fact">
            <dt>输入格式</dt>
            <dd class="bp__mono">{{ item.inputFormat }}</dd>
          </div>
          <div class="bp__fact">
            <dt>输出</dt>
            <dd>{{ item.output }}</dd>
          </div>
          <div class="bp__fact">
            <dt>边界</dt>
            <dd class="bp__limit">{{ item.limit }}</dd>
          </div>
        </dl>

        <!-- DDI 的边界是硬边界：白名单外的药，模型根本没见过 -->
        <div v-if="item.code === 'DDI'" class="bp__whitelist">
          <div class="bp__whitelist-head">
            <span>可预测药物白名单</span>
            <span class="bp__whitelist-count">
              <template v-if="ddiLoading">加载中…</template>
              <template v-else-if="ddiError">读取失败</template>
              <template v-else>{{ ddiDrugs.length }} 条</template>
            </span>
          </div>
          <p v-if="ddiError" class="bp__whitelist-err">{{ ddiError }}</p>
          <div v-else class="bp__chips">
            <span v-for="drug in ddiDrugs.slice(0, 24)" :key="drug.drugId" class="bp__chip">
              {{ drug.drugName || drug.drugId }}
            </span>
            <span v-if="ddiDrugs.length > 24" class="bp__chip bp__chip--more">
              还有 {{ ddiDrugs.length - 24 }} 条
            </span>
            <span v-if="!ddiLoading && !ddiDrugs.length" class="bp__chip bp__chip--more">
              白名单为空
            </span>
          </div>
        </div>
      </article>
    </div>

    <div class="bp__note">
      白名单之外不会「尽量猜」：转导式模型对训练图里没有的药物只能给出没有意义的分数，
      与其返回一个看起来像结论的数字，不如在提交前就告诉用户「这个药不支持」。
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 评价体系 · 能力边界。
 *
 * 三个模型的输入/输出口径来自 `api/predict.ts` 里 `/api/predict/general` 的约定，
 * 属于**契约说明**而非可推导的数据，所以写成常量；白名单是真实数据，必须现取。
 */
import { onMounted, ref } from 'vue'
import { predictApi, type DdiDrug } from '@/api/predict'
import EngineStatusBar from '@/components/evaluation/EngineStatusBar.vue'

interface Boundary {
  code: 'DTI' | 'PPI' | 'DDI'
  name: string
  purpose: string
  inputFormat: string
  output: string
  limit: string
}

const BOUNDARIES: Boundary[] = [
  {
    code: 'DTI',
    name: '药物 — 靶点相互作用',
    purpose: '给定一个小分子和一个靶点，判断两者是否结合，并给出结合强度与置信度。',
    inputFormat: 'SMILES,靶点',
    output: '结合亲和力（kcal/mol）+ 结合置信度（0~1）+ 相互作用残基',
    limit: '靶点可填 UniProt ID 或 PDB 号，后端会自动嗅探并取序列；不认识的靶点直接拒绝，不会用一个近似的蛋白顶替。'
  },
  {
    code: 'PPI',
    name: '蛋白 — 蛋白相互作用',
    purpose: '给定两条蛋白，判断它们是否形成界面。',
    inputFormat: '蛋白序列A,蛋白序列B',
    output: '相互作用置信度（0~1）+ 界面残基对',
    limit: '必须传序列本身，不认蛋白名或基因名 —— 名称到序列的映射有歧义（同源体、异构体），猜错不会报错，只会静默算别的蛋白。'
  },
  {
    code: 'DDI',
    name: '药物 — 药物相互作用',
    purpose: '给定两个已知药物，判断联用时的相互作用风险。',
    inputFormat: '药物A,药物B',
    output: '相互作用概率（0~1）',
    limit: '转导式模型，只能预测训练图内的药物；SMILES 会先转成 DrugBank ID，转不出来即不在白名单。'
  }
]

const ddiDrugs = ref<DdiDrug[]>([])
const ddiLoading = ref(false)
const ddiError = ref('')

async function loadDdiDrugs(): Promise<void> {
  ddiLoading.value = true
  ddiError.value = ''
  try {
    const resp = await predictApi.getDdiDrugs()
    ddiDrugs.value = resp.drugs ?? []
  } catch (error: unknown) {
    ddiError.value = error instanceof Error ? error.message : '白名单读取失败'
  } finally {
    ddiLoading.value = false
  }
}

onMounted(loadDdiDrugs)
</script>

<style lang="scss" scoped>
$bp-warn: rgba(217, 119, 6, 0.12);

.bp {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}

.bp__intro {
  padding: $spacing-md $spacing-lg;
  border: 1px solid $color-border;
  border-left: 3px solid $color-brand;
  border-radius: $radius-card;
  background: $color-surface;
  font-size: $font-size-sm;
  line-height: 1.75;
  color: $color-text-soft;
}

.bp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: $spacing-md;
  align-items: start;
}

.bp__card {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  padding: $spacing-md $spacing-lg;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;
}

.bp__card-head {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.bp__badge {
  padding: 2px 8px;
  border-radius: $radius-control;
  background: $color-brand;
  color: $color-on-dark;
  font-family: $font-family-mono;
  font-size: 10px;
  letter-spacing: 0.04em;
}

.bp__name {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text;
}

.bp__sub {
  margin: 0;
  font-size: $font-size-sm;
  line-height: 1.65;
  color: $color-text-soft;
}

.bp__facts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
}

.bp__fact {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: $spacing-sm;
  align-items: start;

  dt {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  dd {
    margin: 0;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text;
  }
}

.bp__mono {
  font-family: $font-family-mono;
  background: $color-surface-sunken;
  padding: 1px 6px;
  border-radius: $border-radius-sm;
  justify-self: start;
}

.bp__limit {
  color: $color-text-soft;
}

.bp__whitelist {
  margin-top: $spacing-xs;
  padding-top: $spacing-sm;
  border-top: 1px dashed $color-border;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bp__whitelist-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: $font-size-xs;
  color: $color-text-soft;
}

.bp__whitelist-count {
  font-variant-numeric: tabular-nums;
  color: $color-text-faint;
}

.bp__whitelist-err {
  margin: 0;
  font-size: $font-size-xs;
  color: $error-color;
}

.bp__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.bp__chip {
  padding: 1px 8px;
  border-radius: $radius-pill;
  background: $color-surface-sunken;
  color: $color-text-soft;
  font-size: 10px;

  &--more {
    background: $bp-warn;
    color: $warning-color;
  }
}

.bp__note {
  padding: $spacing-sm $spacing-md;
  border-radius: $radius-control;
  background: $bp-warn;
  color: $warning-color;
  font-size: $font-size-xs;
  line-height: 1.7;
}
</style>
