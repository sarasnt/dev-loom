<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { BuildFailure } from '../types'
import { fetchBuildFailure, fetchLatestBuild } from '../api'
import SourceChip from '../components/SourceChip.vue'

const route = useRoute()
const router = useRouter()
const data = ref<BuildFailure | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  // No id in the URL → let the backend resolve the most recent real failed run.
  const id = route.params.id ? String(route.params.id) : ''
  data.value = id ? await fetchBuildFailure(id) : await fetchLatestBuild()
  loading.value = false
}
onMounted(load)
watch(() => route.params.id, load)
</script>

<template>
  <main class="bf">
    <div v-if="loading" class="mono empty" aria-busy="true">analyzing build…</div>

    <!-- Honest empty state — no failing runs (or GitHub not configured) -->
    <div v-else-if="data && data.id === 'none'" class="empty-state">
      <div class="head"><h1>Build failure</h1></div>
      <p class="prose">{{ data.summary }}</p>
      <ul class="diag">
        <li v-for="(d, i) in data.diagnostics" :key="i">{{ d }}</li>
      </ul>
    </div>

    <template v-else-if="data">
      <div class="head">
        <h1>Build failure · run <span class="mono">{{ data.run }}</span></h1>
        <span class="when">{{ data.branch }}<template v-if="data.pr"> · PR #{{ data.pr }}</template> · {{ data.failedAgo }}</span>
      </div>

      <section class="step">
        <div class="n mono">① SUMMARY</div>
        <p class="prose">
          {{ data.summary }}
          <span class="pill hi mono">conf: {{ data.summaryConfidence }}</span>
          <span class="reason-mark" aria-label="model reasoning">reasoning°</span>
        </p>
      </section>

      <section class="step">
        <div class="n mono">② FAILING STEP · ③ LOG EXCERPT</div>
        <div class="tags">
          <span class="tag ok mono" v-if="data.redacted">redacted ✓</span>
          <span class="tag mono">first-failure region</span>
          <span class="tag mono link">full log ↗</span>
        </div>
        <pre class="log mono"><template v-for="(l, i) in data.log" :key="i"><span :class="l.kind">{{ l.text }}</span>
</template></pre>
      </section>

      <section class="step">
        <div class="n mono">④ RANKED CAUSES</div>
        <div v-for="c in data.causes" :key="c.rank" class="cause">
          <span class="rk mono">{{ c.rank }}</span>
          <div class="cbody">
            <div :class="{ dimmed: c.uncited }">
              {{ c.text }}
              <span v-if="!c.uncited" class="reason-mark" aria-label="model reasoning">reasoning°</span>
            </div>
            <div class="thread mono">
              evidence →
              <template v-if="c.evidence.length">
                <SourceChip v-for="e in c.evidence" :key="e.id" :ref-item="e" />
              </template>
              <span v-else class="uncited">(none in diff) · uncited</span>
            </div>
          </div>
          <span class="pill mono" :class="c.confidence === 'high' ? 'hi' : 'lo'">{{ c.confidence }}</span>
        </div>
      </section>

      <section class="step">
        <div class="n mono">⑤ RELATED · ⑥ DIAGNOSTICS · ⑦ FIXES</div>
        <div class="chips">
          <SourceChip v-for="r in data.related" :key="r.id" :ref-item="r" />
        </div>
        <ol class="diag">
          <li v-for="(d, i) in data.diagnostics" :key="i">{{ d }}</li>
        </ol>
        <p class="prose">{{ data.fixes[0] }} <span class="reason-mark">reasoning°</span></p>
      </section>

      <div class="handoffbar">
        <span class="ic" aria-hidden="true">⇥</span>
        <span class="txt">Generate agent handoff</span>
        <button class="btn pri" @click="router.push('/handoffs/h1')">Generate ▸</button>
      </div>
    </template>
  </main>
</template>

<style scoped>
.bf { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; }
.head h1 { font-size: 22px; }
.when { font-size: 12px; color: var(--faint-text); }
.empty { color: var(--faint-text); padding: 24px 0; }
.step { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 14px 16px; margin-bottom: 14px; }
.n { font-size: 11px; color: var(--warp-hi); letter-spacing: 0.08em; }
.prose { color: var(--dim); font-size: 14px; margin: 8px 0 0; line-height: 1.55; }
.reason-mark {
  font-family: var(--mono); font-size: 10px; color: var(--warp);
  border: 1px solid var(--warp); border-radius: 4px; padding: 1px 5px; margin-left: 6px; white-space: nowrap;
}
.pill { font-size: 10px; padding: 2px 8px; border-radius: 6px; border: 1px solid; display: inline-block; }
.pill.hi { color: var(--warp-hi); border-color: var(--warp); }
.pill.lo { color: var(--stale); border-color: var(--stale); }
.tags { display: flex; gap: 8px; margin: 8px 0 10px; }
.tag { font-size: 10px; border: 1px solid var(--line); border-radius: 5px; padding: 2px 6px; color: var(--dim); }
.tag.ok { color: var(--healthy); border-color: var(--healthy); }
.tag.link { margin-left: auto; }
.log {
  font-size: 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px;
  padding: 12px; color: var(--dim); line-height: 1.7; overflow: auto; margin: 0; white-space: pre-wrap;
}
.log .fail { color: var(--chip-fail); }
.log .omitted { color: var(--faint-text); }
.log .redacted { color: var(--warp-hi); }
.cause { display: flex; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.cause:last-child { border: 0; }
.rk { font-size: 12px; color: var(--warp-hi); }
.cbody { flex: 1; color: var(--ink); font-size: 14px; }
.cbody .dimmed { color: var(--dim); }
.thread { font-size: 11px; color: var(--warp-hi); margin-top: 6px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.thread .uncited { color: var(--stale); }
.chips { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 10px; }
.diag { color: var(--dim); font-size: 14px; margin: 0 0 6px; padding-left: 20px; }
.handoffbar {
  border: 1px solid var(--warp); border-radius: var(--r-card); padding: 14px 16px;
  background: var(--warp-weft); display: flex; align-items: center; gap: 12px; margin-top: 6px;
}
.handoffbar .ic { color: var(--warp-hi); }
.handoffbar .txt { font-weight: 600; }
.handoffbar .btn { margin-left: auto; }
.btn { font-size: 13px; font-weight: 600; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--warp); background: var(--warp); color: var(--on-warp); }
.btn.pri:hover { background: var(--warp-hi); }
</style>
