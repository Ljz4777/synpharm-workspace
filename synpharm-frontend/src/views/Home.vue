<template>
  <div class="home">
    <header class="hd">
      <div class="hd__inner">
        <router-link to="/" class="hd__brand">
          <span class="hd__mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor"
                 stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3.2 19 7.1v8.5L12 20.8 5 15.6V7.1l7-3.9Z" />
              <path d="M12 10.7a1.35 1.35 0 1 0 0 2.7 1.35 1.35 0 0 0 0-2.7Z" fill="currentColor" stroke="none" />
            </svg>
          </span>
          <span class="hd__brand-text">
            <span class="hd__name">SynPharm</span>
            <span class="hd__tagline">AI 药物研发平台</span>
          </span>
        </router-link>

        <nav class="hd__nav">
          <a v-for="l in NAV" :key="l.href" :href="l.href" class="hd__nav-link">{{ l.label }}</a>
        </nav>

        <div class="hd__actions">
          <router-link to="/login" class="btn btn--ghost">登录</router-link>
          <button type="button" class="btn btn--primary" :disabled="entering" @click="enterPlatform">
            {{ entering ? '进入中…' : authStore.isLoggedIn ? '进入平台' : '免费体验' }}
          </button>
        </div>
      </div>
    </header>

    <!-- ============================ Hero ============================ -->
    <section class="hero">
      <div class="hero__inner">
        <div class="hero__text">
          <p class="hero__eyebrow">药物设计 · 分子预测 一体化平台</p>
          <h1 class="hero__title">
            从靶点到候选<br />
            <span class="hero__title-em">每一步都留痕</span>
          </h1>
          <p class="hero__lead">
            需求基线、候选生成、评估编辑、决策四步各自留下可回放的快照与指纹；
            预测域提供 PPI / DTI / DDI 与三维结构可视化。所有数值由引擎实算，没有估算值冒充结论。
          </p>

          <div class="hero__actions">
            <button type="button" class="btn btn--primary btn--lg" :disabled="entering" @click="enterPlatform">
              {{ entering ? '进入中…' : authStore.isLoggedIn ? '进入平台' : '游客体验（无需注册）' }}
            </button>
            <a href="#design" class="btn btn--outline btn--lg">看看怎么走完一轮</a>
          </div>

          <!-- 三句都是可以在系统里被验证的事实，不是宣传数字 -->
          <ul class="hero__facts">
            <li v-for="f in FACTS" :key="f" class="hero__fact">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
                   stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M4.5 12.5l5 5 10-11" />
              </svg>
              <span>{{ f }}</span>
            </li>
          </ul>
        </div>

        <!-- 右：流程图卡。这是产品的真实骨架，不是装饰 -->
        <div class="hero__panel">
          <header class="panel__head">
            <span class="panel__title">主业务流程</span>
            <span class="panel__chip">四步都可回放</span>
          </header>
          <ol class="flow">
            <li v-for="(s, i) in STEPS" :key="s.key" class="flow__row">
              <span class="flow__no">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="flow__body">
                <span class="flow__label">{{ s.label }}</span>
                <span class="flow__hint">{{ s.hint }}</span>
              </span>
              <span class="flow__trace">{{ s.trace }}</span>
            </li>
          </ol>
          <footer class="panel__foot">
            决策只读已冻结的证据包 —— 不读候选的当前状态，所以同一份输入重算会得到同一份名单。
          </footer>
        </div>
      </div>
    </section>

    <!-- ============================ 设计主线 ============================ -->
    <section id="design" class="sec">
      <div class="sec__inner">
        <header class="sec__head">
          <p class="sec__eyebrow">药物设计</p>
          <h2 class="sec__title">四步走完一轮设计，每步都留下可回放的痕迹</h2>
          <p class="sec__lead">下一阶段的输入永远是上一阶段冻结出来的快照，而不是「现在的数据」。</p>
        </header>

        <div class="cards">
          <article v-for="(s, i) in STEPS" :key="s.key" class="card">
            <span class="card__no">{{ String(i + 1).padStart(2, '0') }}</span>
            <h3 class="card__title">{{ s.label }}</h3>
            <p class="card__desc">{{ s.detail }}</p>
            <p class="card__note">{{ s.note }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ============================ 分子预测 ============================ -->
    <section id="predict" class="sec sec--alt">
      <div class="sec__inner">
        <header class="sec__head">
          <p class="sec__eyebrow">分子预测</p>
          <h2 class="sec__title">三条预测线 + 三维结构可视化</h2>
          <p class="sec__lead">与设计流程独立的一条产品线：给序列或结构，得到可解释的相互作用预测。</p>
        </header>

        <div class="cards cards--4">
          <article v-for="p in PREDICT" :key="p.label" class="card card--mini">
            <span class="card__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
                   stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, k) in p.icon" :key="k" :d="d" />
              </svg>
            </span>
            <h3 class="card__title">{{ p.label }}</h3>
            <p class="card__desc">{{ p.desc }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ============================ 平台承诺 ============================ -->
    <section id="platform" class="sec">
      <div class="sec__inner">
        <header class="sec__head">
          <p class="sec__eyebrow">平台承诺</p>
          <h2 class="sec__title">三条不可绕过的规则</h2>
          <p class="sec__lead">它们不是界面提示，是代码里的硬校验 —— 违反时接口直接拒绝。</p>
        </header>

        <div class="rules">
          <div v-for="r in RULES" :key="r.title" class="rule">
            <span class="rule__badge">{{ r.badge }}</span>
            <h3 class="rule__title">{{ r.title }}</h3>
            <p class="rule__desc">{{ r.desc }}</p>
          </div>
        </div>

        <div class="stack">
          <span class="stack__label">技术底座</span>
          <ul class="stack__list">
            <li v-for="t in STACK" :key="t" class="stack__item">{{ t }}</li>
          </ul>
        </div>
      </div>
    </section>

    <!-- ============================ 底部 CTA ============================ -->
    <section class="cta">
      <div class="cta__inner">
        <h2 class="cta__title">不用注册也能先跑一轮</h2>
        <p class="cta__desc">
          游客身份同样拥有完整功能，数据只归属你自己；点「一键体验」会自动建项目、冻结基线、
          跑一批候选、派生一版并生成决策。
        </p>
        <div class="cta__actions">
          <button type="button" class="btn btn--primary btn--lg" :disabled="entering" @click="enterPlatform">
            {{ entering ? '进入中…' : '游客体验' }}
          </button>
          <router-link to="/login" class="btn btn--outline btn--lg">注册账号</router-link>
        </div>
      </div>
    </section>

    <footer class="ft">
      <div class="ft__inner">
        <div class="ft__brand">
          <span class="hd__mark hd__mark--sm" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                 stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3.2 19 7.1v8.5L12 20.8 5 15.6V7.1l7-3.9Z" />
              <path d="M12 10.7a1.35 1.35 0 1 0 0 2.7 1.35 1.35 0 0 0 0-2.7Z" fill="currentColor" stroke="none" />
            </svg>
          </span>
          <span>SynPharm · AI 药物研发平台</span>
        </div>
        <nav class="ft__links">
          <a v-for="l in NAV" :key="l.href" :href="l.href" class="ft__link">{{ l.label }}</a>
          <router-link to="/login" class="ft__link">登录</router-link>
        </nav>
        <span class="ft__copy">© {{ year }} SynPharm</span>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
/**
 * 落地页。
 *
 * 这一版**删掉了原来的「快速预测演示」**：它用 `Math.random()` 造结合亲和力、
 * 置信度和相互作用，延迟 1.5 秒后当成预测结果展示。那不是演示，是编数据 ——
 * 而本项目从一开始就坚持「算不出来就说算不出来」（缺指标留空、判不了就标
 * 待判定）。落地页更不该反着来。要看真实效果，点「游客体验」进系统跑一轮，
 * 那条路径上的每个数字都来自引擎与数据库。
 *
 * 同样删掉了「10万+ 预测任务 / 95% 准确率 / 500+ 支持靶点」这类没有出处的数字，
 * 换成三句可以在系统里被验证的事实（引擎实算、否决不可绕过、结论可回放）。
 *
 * 图标全部内联 SVG：emoji 的字形与基线由操作系统字体决定，跨平台不一致，
 * 还会在缺字体环境里退化成方块。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const year = computed(() => new Date().getFullYear())

const NAV = [
  { href: '#design', label: '设计流程' },
  { href: '#predict', label: '分子预测' },
  { href: '#platform', label: '平台承诺' }
]

/** 三句都能在系统里验证，不是宣传口径 */
const FACTS = ['引擎实算，不猜值', '否决不可绕过', '结论可回放']

interface Step {
  key: string
  label: string
  hint: string
  trace: string
  detail: string
  note: string
}

const STEPS: Step[] = [
  {
    key: 'baseline',
    label: '需求分析',
    hint: '八块基线，不齐不允许冻结',
    trace: '冻结',
    detail:
      '靶点 / 结构 / 分子形式 / 硬约束 / 软约束 / 指标 / 配额 / 风险，八块逐块预检；缺哪块、缺到什么程度都直接列出来，不用猜。',
    note: '冻结后生成的快照是后续所有阶段的唯一输入'
  },
  {
    key: 'generation',
    label: '候选生成',
    hint: '去重 → 粗筛 → 多样性配额',
    trace: '留因',
    detail:
      '每个被筛掉的分子都留下结构化原因（哪条约束、差多少），而不是一句「不合格」。分批可重放，同一批种子得到同一批候选。',
    note: '逐分子去向可查：进了配额、被去重、还是被粗筛拦下'
  },
  {
    key: 'evaluation',
    label: '评估与编辑',
    hint: '冻结分数 → 缺口 → 动作 → 影响面',
    trace: '快照',
    detail:
      '评估结果按版本冻结，缺口按维度诊断；改一个基团之前先算影响面 —— 会不会伤到已经达标的维度，先说清楚再动手。',
    note: '缺数据的指标留空并如实告知覆盖率，不补零'
  },
  {
    key: 'decision',
    label: '决策',
    hint: '证据包 → 复核 → 否决 → 名额',
    trace: '指纹',
    detail:
      '装配证据包、硬约束复核、业务性否决、分桶排序、按「档位与湿实验余额取小」分配名额；确认前会重算一遍比对指纹。',
    note: 'BLOCK 级否决只能改数据重来，不接受在决策层划掉'
  }
]

const PREDICT = [
  {
    label: 'PPI 预测',
    desc: '蛋白质-蛋白质相互作用，揭示细胞内分子机制',
    icon: [
      'M8.4 8.6a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8Z',
      'M15.6 8.6a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8Z',
      'M11.8 12h.4'
    ]
  },
  {
    label: 'DTI 预测',
    desc: '药物-靶点相互作用，给候选分子排出优先级',
    icon: [
      'M9.4 6.6h5.2a4.4 4.4 0 0 1 0 8.8H9.4a4.4 4.4 0 0 1 0-8.8Z',
      'M9.4 12h5.2'
    ]
  },
  {
    label: 'DDI 预测',
    desc: '药物-药物相互作用，评估联合用药安全性',
    icon: [
      'M13.2 4.6 5 12.8l3.4 3.4L16.6 8 13.2 4.6Z',
      'M10.8 19.4 18 12.2l-3.4-3.4',
      'M6.6 15.4l2 2'
    ]
  },
  {
    label: '三维结构可视化',
    desc: 'PDB 结构与口袋渲染，结合模式直接看',
    icon: [
      'M12 3.4 19.6 7.7v8.6L12 20.6 4.4 16.3V7.7L12 3.4Z',
      'M12 12l7.6-4.3M12 12v8.6M12 12 4.4 7.7'
    ]
  }
]

const RULES = [
  {
    badge: '证据',
    title: '证据先于判断',
    desc: '没有冻结证据包的候选进不了决策表。换个人、换个时间重算，读到的还是同一份证据。'
  },
  {
    badge: '否决',
    title: '否决不可绕过',
    desc: 'BLOCK 级否决与硬约束不达标只能改数据后重新决策，不能在决策层手动改成「入选」。'
  },
  {
    badge: '确认',
    title: '确认即不可改',
    desc: '确认之后任何去向都不能再改，要改就新建一版并指向上一版 —— 历史只增不删。'
  }
]

const STACK = [
  'RDKit 实算理化指标',
  'Indigo 二维画布',
  'Mol* 结构渲染',
  'Spring Cloud 微服务',
  'RabbitMQ 任务队列'
]

const entering = ref(false)

/**
 * 进入平台。
 * 未登录时用游客身份真实登录一次（不是假的跳转）——
 * 游客同样有完整功能，数据归属自己。
 */
async function enterPlatform() {
  if (authStore.isLoggedIn) {
    router.push('/projects')
    return
  }
  entering.value = true
  try {
    const result = await authStore.login({ loginType: 'guest' })
    if (result.success) {
      router.push('/projects')
    } else {
      router.push('/login')
    }
  } finally {
    entering.value = false
  }
}
</script>

<style lang="scss" scoped>
.home {
  min-height: 100vh;
  background: $color-surface;
}

/* ---------------- 通用按钮（落地页不依赖全局 .btn 之外的样式） ---------------- */

.btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  height: 36px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: 8px;
  font-family: inherit;
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  color: $color-text;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
  transition: background $transition-fast, color $transition-fast, border-color $transition-fast;

  &--primary {
    color: #fff;
    background: $color-brand;

    &:hover {
      background: $color-brand-strong;
    }
  }

  &--outline {
    border-color: $color-border;
    background: transparent;

    &:hover {
      border-color: $color-brand;
      color: $color-brand;
    }
  }

  &--ghost {
    padding: 0 10px;
    color: $color-text-faint;
    background: transparent;

    &:hover {
      color: $color-brand;
    }
  }

  &--lg {
    height: 44px;
    padding: 0 22px;
    font-size: $font-size-base;
    border-radius: 10px;
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  &:focus-visible {
    outline: 2px solid $color-brand;
    outline-offset: 2px;
  }
}

/* ---------------- 顶栏 ---------------- */

.hd {
  position: sticky;
  top: 0;
  z-index: $z-sticky;
  border-bottom: 1px solid $border-light;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);

  &__inner {
    display: flex;
    gap: $spacing-lg;
    align-items: center;
    max-width: 1160px;
    margin: 0 auto;
    padding: 12px $spacing-xl;
  }

  &__brand {
    display: flex;
    gap: 10px;
    align-items: center;
    color: inherit;
    text-decoration: none;
  }

  &__mark {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    border: 1px solid rgba(13, 148, 136, 0.28);
    border-radius: 9px;
    background: $color-brand-soft;
    color: $color-brand;

    &--sm {
      width: 24px;
      height: 24px;
      border-radius: 7px;
    }
  }

  &__brand-text {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  &__name {
    font-size: 15px;
    font-weight: $font-weight-semibold;
    line-height: 1.2;
    color: $color-text;
  }

  &__tagline {
    font-size: 10px;
    line-height: 1.2;
    color: $color-text-faint;
  }

  &__nav {
    display: flex;
    gap: $spacing-lg;
    margin-left: $spacing-lg;
  }

  &__nav-link {
    font-size: $font-size-sm;
    color: $color-text-faint;
    text-decoration: none;
    transition: color $transition-fast;

    &:hover {
      color: $color-brand;
    }
  }

  &__actions {
    display: flex;
    gap: 6px;
    align-items: center;
    margin-left: auto;
  }
}

/* ---------------- Hero ---------------- */

.hero {
  border-bottom: 1px solid $border-light;
  background:
    radial-gradient(900px 380px at 88% -8%, rgba(13, 148, 136, 0.09), transparent 60%),
    $bg-secondary;

  &__inner {
    display: grid;
    grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
    gap: $spacing-2xl;
    align-items: center;
    max-width: 1160px;
    margin: 0 auto;
    padding: 72px $spacing-xl 80px;
  }

  &__eyebrow {
    display: inline-block;
    margin-bottom: $spacing-md;
    padding: 4px 10px;
    border: 1px solid rgba(13, 148, 136, 0.22);
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    letter-spacing: 0.02em;
    color: $color-brand-strong;
    background: $color-brand-softer;
  }

  &__title {
    margin: 0 0 $spacing-md;
    font-size: 44px;
    font-weight: $font-weight-semibold;
    line-height: 1.2;
    letter-spacing: -0.01em;
    color: $color-text;
  }

  &__title-em {
    // 渐变文字比纯色更轻，但只在标题上用，避免全站花哨
    background: linear-gradient(96deg, $color-brand 10%, $accent-light 90%);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }

  &__lead {
    max-width: 30em;
    margin: 0 0 $spacing-xl;
    font-size: $font-size-base;
    line-height: 1.75;
    color: $text-secondary;
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    margin-bottom: $spacing-lg;
  }

  &__facts {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm $spacing-lg;
    padding: 0;
    margin: 0;
    list-style: none;
  }

  &__fact {
    display: flex;
    gap: 6px;
    align-items: center;
    font-size: $font-size-xs;
    color: $color-text-faint;

    svg {
      color: $success-color;
    }
  }
}

/* ---------------- Hero 右侧流程卡 ---------------- */

.hero__panel {
  padding: $spacing-lg;
  border: 1px solid $color-border;
  border-radius: 14px;
  background: $color-surface;
  box-shadow: 0 18px 40px -24px rgba(15, 43, 44, 0.35);
}

.panel {
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 10px;
    margin-bottom: 6px;
    border-bottom: 1px solid $border-light;
  }

  &__title {
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__chip {
    padding: 2px 8px;
    border-radius: $radius-pill;
    font-size: 10px;
    color: $color-brand-strong;
    background: $color-brand-soft;
  }

  &__foot {
    padding-top: 10px;
    margin-top: 8px;
    border-top: 1px solid $border-light;
    font-size: $font-size-xs;
    line-height: 1.7;
    color: $color-text-faint;
  }
}

.flow {
  padding: 0;
  margin: 0;
  list-style: none;

  &__row {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    padding: 10px 0;

    & + & {
      border-top: 1px dashed $border-light;
    }
  }

  &__no {
    flex-shrink: 0;
    width: 22px;
    font-family: $font-family-mono;
    font-size: 11px;
    line-height: 20px;
    color: $color-brand;
  }

  &__body {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
  }

  &__label {
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $color-text;
  }

  &__hint {
    font-size: $font-size-xs;
    line-height: 1.5;
    color: $color-text-faint;
  }

  &__trace {
    flex-shrink: 0;
    align-self: center;
    padding: 1px 7px;
    border-radius: 4px;
    font-size: 10px;
    color: $text-secondary;
    background: $color-surface-sunken;
  }
}

/* ---------------- 区块 ---------------- */

.sec {
  padding: 76px $spacing-xl;

  &--alt {
    border-top: 1px solid $border-light;
    border-bottom: 1px solid $border-light;
    background: $bg-secondary;
  }

  &__inner {
    max-width: 1160px;
    margin: 0 auto;
  }

  &__head {
    max-width: 44em;
    margin-bottom: $spacing-2xl;
  }

  &__eyebrow {
    margin: 0 0 10px;
    font-size: $font-size-xs;
    font-weight: $font-weight-semibold;
    letter-spacing: 0.08em;
    color: $color-brand;
  }

  &__title {
    margin: 0 0 $spacing-sm;
    font-size: 28px;
    font-weight: $font-weight-semibold;
    line-height: 1.35;
    color: $color-text;
  }

  &__lead {
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.8;
    color: $text-secondary;
  }
}

/* ---------------- 卡片 ---------------- */

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: $spacing-md;

  &--4 {
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  }
}

.card {
  display: flex;
  flex-direction: column;
  padding: $spacing-lg;
  border: 1px solid $color-border;
  border-radius: 12px;
  background: $color-surface;
  transition: border-color $transition-fast, box-shadow $transition-fast;

  &:hover {
    border-color: rgba(13, 148, 136, 0.35);
    box-shadow: 0 12px 28px -22px rgba(15, 43, 44, 0.4);
  }

  &--mini {
    padding: $spacing-md;
  }

  &__no {
    margin-bottom: 10px;
    font-family: $font-family-mono;
    font-size: 12px;
    font-weight: $font-weight-semibold;
    color: $color-brand;
  }

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 38px;
    height: 38px;
    margin-bottom: 12px;
    border-radius: 10px;
    color: $color-brand;
    background: $color-brand-soft;
  }

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__desc {
    flex: 1;
    margin: 0 0 10px;
    font-size: $font-size-sm;
    line-height: 1.7;
    color: $text-secondary;
  }

  &__note {
    margin: 0;
    padding-top: 10px;
    border-top: 1px solid $border-light;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;
  }
}

/* ---------------- 三条规则 ---------------- */

.rules {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: $spacing-md;
  margin-bottom: $spacing-2xl;
}

.rule {
  padding: $spacing-lg;
  border: 1px solid $color-border;
  border-left: 3px solid $color-brand;
  border-radius: 12px;
  background: $color-surface;

  &__badge {
    display: inline-block;
    margin-bottom: 10px;
    padding: 1px 8px;
    border-radius: $radius-pill;
    font-size: 10px;
    color: $color-brand-strong;
    background: $color-brand-soft;
  }

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__desc {
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.7;
    color: $text-secondary;
  }
}

.stack {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 0;
    margin: 0;
    list-style: none;
  }

  &__item {
    padding: 4px 10px;
    border: 1px solid $color-border;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    color: $text-secondary;
    background: $color-surface;
  }
}

/* ---------------- 底部 CTA ---------------- */

.cta {
  padding: 76px $spacing-xl;
  border-top: 1px solid $border-light;
  background:
    radial-gradient(700px 300px at 50% 120%, rgba(13, 148, 136, 0.12), transparent 65%),
    $bg-secondary;

  &__inner {
    max-width: 640px;
    margin: 0 auto;
    text-align: center;
  }

  &__title {
    margin: 0 0 $spacing-sm;
    font-size: 26px;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__desc {
    margin: 0 0 $spacing-lg;
    font-size: $font-size-sm;
    line-height: 1.8;
    color: $text-secondary;
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    justify-content: center;
  }
}

/* ---------------- 页脚 ---------------- */

.ft {
  border-top: 1px solid $border-light;
  background: $color-surface;

  &__inner {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-md;
    align-items: center;
    max-width: 1160px;
    margin: 0 auto;
    padding: $spacing-lg $spacing-xl;
  }

  &__brand {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__links {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-md;
    margin-left: auto;
  }

  &__link {
    font-size: $font-size-xs;
    color: $color-text-faint;
    text-decoration: none;

    &:hover {
      color: $color-brand;
    }
  }

  &__copy {
    font-size: $font-size-xs;
    color: $text-light;
  }
}

/* ---------------- 窄屏 ---------------- */

@media (max-width: 900px) {
  .hero__inner {
    grid-template-columns: minmax(0, 1fr);
    padding: 48px $spacing-lg 56px;
  }

  .hero__title {
    font-size: 32px;
  }

  .hd__nav {
    display: none;
  }

  .sec,
  .cta {
    padding: 48px $spacing-lg;
  }

  .sec__title,
  .cta__title {
    font-size: 22px;
  }
}
</style>
