<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { BuildFailure } from '../types'
import { fetchBuildFailure, fetchLatestBuild, fetchWork, generateHandoff } from '../api'
import { useDashboardStore } from '../stores/dashboard'
import SourceChip from '../components/SourceChip.vue'
import LoomLoader from '../components/LoomLoader.vue'
import ModelSelect from '../components/ModelSelect.vue'
import { renderMarkdown } from '../utils/markdown'

const API = (import.meta.env.VITE_API_BASE as string) ?? '/api/v1'

const route = useRoute()
const router = useRouter()
const store = useDashboardStore()
// Opens with the Builds default (Settings > General); the on-screen picker overrides it just
// for the current run without changing the default. Agent modes excluded (Brainstorm-only).
const runModel = ref('')
function pickRun(m: string) {
  runModel.value = m
  store.reflectModel(m)
}

const data = ref<BuildFailure | null>(null)
const loading = ref(true)
const step = ref('Starting analysis…')
const progress = ref(0)
const resultModel = ref('') // the model that produced the on-screen summary

// Every failing run, so this screen isn't a view of whichever one you analysed last. The work
// model already carries them, so no new endpoint — a build item's id is its CI run id.
const failing = ref<{ id: string; title: string; repo: string }[]>([])
async function loadFailing() {
  try {
    const rows = await fetchWork()
    failing.value = rows
      .filter((r) => r.type === 'build')
      .map((r) => ({ id: r.id, title: r.title, repo: (r.meta ?? []).slice(-1)[0] ?? '' }))
  } catch { failing.value = [] }
}
function switchTo(id: string) {
  if (id && id !== currentId.value) router.push(`/builds/${id}`)
}
// What the selector should read as: the run actually on screen. The route has no id when you
// arrive at /builds (the backend picks the newest failure), so keying off the route alone left
// the selector blank on the very page it's most useful.
const currentId = computed(() => String(route.params.id ?? '') || data.value?.id || '')

// Generate and KEEP it, then open the saved one — a handoff you can't come back to isn't an
// artifact, it's a view.
const generating = ref(false)
async function makeHandoff() {
  if (generating.value) return
  generating.value = true
  try {
    const h = await generateHandoff(currentId.value || undefined)
    router.push(`/handoffs/${h.id}`)
  } catch {
    router.push('/handoffs/h1')
  } finally { generating.value = false }
}
let es: EventSource | null = null

function closeStream() {
  if (es) {
    es.close()
    es = null
  }
}

// Stream the real analysis stages over SSE; fall back to a one-shot fetch if unavailable.
function analyze() {
  closeStream()
  loading.value = true
  data.value = null
  step.value = 'Connecting…'
  progress.value = 3
  const id = route.params.id ? String(route.params.id) : ''
  const m = runModel.value ? `?model=${encodeURIComponent(runModel.value)}` : ''
  const url = `${API}/builds/${id ? id + '/stream' : 'stream'}${m}`
  try {
    es = new EventSource(url)
  } catch {
    loadFallback()
    return
  }
  let seen = 0
  es.addEventListener('step', (e) => {
    step.value = (e as MessageEvent).data
    seen++
    // Count-based, not a hardcoded stage total: GitHub emits 4 stages, Bitbucket only 3 (no log
    // to redact), so a fixed 4-stage curve stalled at 58% then jumped for Bitbucket. This stays
    // monotone for either — 30/60/90 or 30/60/90/95 — then holds until 'result' sets 100.
    progress.value = Math.min(95, seen * 30)
  })
  es.addEventListener('result', (e) => {
    try {
      data.value = JSON.parse((e as MessageEvent).data) as BuildFailure
      resultModel.value = data.value?.analyzedBy ?? ''
    } catch {
      /* leave data null → fallback below via error */
    }
    progress.value = 100
    loading.value = false
    closeStream()
  })
  es.onerror = () => {
    closeStream()
    if (loading.value) loadFallback()
  }
}

async function loadFallback() {
  const id = route.params.id ? String(route.params.id) : ''
  try {
    data.value = id ? await fetchBuildFailure(id, runModel.value) : await fetchLatestBuild(runModel.value)
    resultModel.value = data.value?.analyzedBy ?? ''
  } finally {
    loading.value = false
  }
}

// analyzedBy carries a provenance suffix for metadata-only analyses ("<model> · metadata only
// (no log available)") — the honest label to *display*, but not the model identity to *compare*.
// resultModelName strips it once so canRedo (and the deterministic-suppression check below) test
// identity, not provenance; the full resultModel string still renders in the label itself.
const resultModelName = computed(() => (resultModel.value || '').split(' · ')[0])
const metadataOnly = computed(() => resultModel.value.includes('metadata only'))

// Offer a re-run when the selected model differs from the one that produced the summary.
const canRedo = computed(
  () =>
    !loading.value &&
    !!data.value &&
    data.value.id !== 'none' &&
    !!resultModelName.value &&
    resultModelName.value !== 'deterministic' &&
    !!runModel.value &&
    runModel.value !== resultModelName.value,
)

onMounted(async () => {
  await store.ensureLoaded()
  // "Analyze with X" from a Today card passes ?model; otherwise open with the configured default.
  runModel.value = (route.query.model as string) || store.modelFor('builds')
  store.reflectModel(runModel.value)
  loadFailing()
  analyze()
})
watch(() => route.params.id, analyze)
onUnmounted(closeStream)
</script>

<template>
  <main class="bf">
    <div v-if="loading" class="loadwrap" aria-busy="true">
      <LoomLoader :step="step" :progress="progress" />
    </div>

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
        <div class="headright">
          <label v-if="failing.length > 1" class="picker mono">
            <span class="plab">failure</span>
            <select class="psel" :value="currentId" @change="switchTo(($event.target as HTMLSelectElement).value)">
              <option v-for="f in failing" :key="f.id" :value="f.id">{{ f.title }}</option>
            </select>
          </label>
          <ModelSelect screen="builds" label="run with" manual :model-value="runModel" @change="pickRun" />
          <span class="when">{{ data.branch }}<template v-if="data.pr"> · PR #{{ data.pr }}</template> · {{ data.failedAgo }}</span>
        </div>
      </div>

      <!-- Offered when you switch models after this was analyzed -->
      <div v-if="canRedo" class="redo">
        <span>
          Analyzed by <b class="mono">{{ resultModel }}</b> · you've switched to
          <b class="mono">{{ runModel }}</b>.
        </span>
        <button class="redo-btn" @click="analyze()">Re-run with {{ runModel }} ↻</button>
      </div>

      <section class="step">
        <div class="n mono">① SUMMARY</div>
        <div class="prose md" v-html="renderMarkdown(data.summary)"></div>
        <div class="pillrow">
          <span class="pill hi mono">conf: {{ data.summaryConfidence }}</span>
          <span v-if="data.analyzedBy && resultModelName !== 'deterministic'" class="pill mono">{{ data.analyzedBy }}</span>
          <span class="reason-mark" aria-label="model reasoning">reasoning°</span>
        </div>
      </section>

      <section class="step">
        <div class="n mono">② FAILING STEP · ③ LOG EXCERPT</div>
        <div class="tags">
          <span class="tag ok mono" v-if="data.redacted">redacted ✓</span>
          <span class="tag mono" v-if="!metadataOnly">first-failure region</span>
          <span class="tag mono link" v-if="!metadataOnly">full log ↗</span>
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
              <span class="md" v-html="renderMarkdown(c.text)"></span>
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
        <p class="prose"><span class="md" v-html="renderMarkdown(data.fixes[0])"></span> <span class="reason-mark">reasoning°</span></p>
      </section>

      <div class="handoffbar">
        <span class="ic" aria-hidden="true">⇥</span>
        <span class="txt">Generate agent handoff</span>
        <button class="btn pri" :disabled="generating" @click="makeHandoff">{{ generating ? 'Generating…' : 'Generate ▸' }}</button>
      </div>
    </template>
  </main>
</template>

<style scoped>
.bf { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; gap: 14px; flex-wrap: wrap; }
.head h1 { font-size: 22px; }
.picker { display: flex; align-items: center; gap: 8px; }
.plab { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--faint-text); }
.psel { background: var(--bg); border: 1px solid var(--line); border-radius: 6px; color: var(--ink); padding: 5px 8px; font-family: var(--mono); font-size: 11.5px; max-width: 340px; }
.psel:focus { outline: none; border-color: var(--warp); }
.headright { display: flex; align-items: center; gap: 14px; }
.when { font-size: 12px; color: var(--faint-text); }
.empty { color: var(--faint-text); padding: 24px 0; }
.loadwrap { display: flex; justify-content: center; padding: 64px 0; }
.redo {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  border: 1px solid var(--warp); background: var(--warp-weft);
  border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; font-size: 13px; color: var(--dim);
}
.redo-btn {
  margin-left: auto; font-size: 12.5px; font-weight: 600; border-radius: var(--r-ctl);
  padding: 6px 12px; border: 1px solid var(--warp); background: var(--warp); color: var(--on-warp);
  cursor: pointer; white-space: nowrap;
}
.redo-btn:hover { background: var(--warp-hi); }
.pill { margin-left: 6px; }
.step { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 14px 16px; margin-bottom: 14px; }
.n { font-size: 11px; color: var(--warp-hi); letter-spacing: 0.08em; }
.prose { color: var(--dim); font-size: 14px; margin: 8px 0 0; line-height: 1.55; }
.pillrow { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 8px; }
/* rendered markdown in model output (summary, causes, fixes) */
.md :deep(.md-p) { margin: 0 0 8px; } .md :deep(.md-p:last-child) { margin-bottom: 0; }
.md :deep(.md-h) { font-weight: 600; color: var(--ink); margin: 10px 0 5px; }
.md :deep(.md-ul), .md :deep(.md-ol) { margin: 4px 0 8px; padding-left: 20px; }
.md :deep(li) { margin: 2px 0; }
.md :deep(strong) { color: var(--ink); font-weight: 600; }
.md :deep(.md-code) { font-family: var(--mono); font-size: 12px; background: var(--chip-bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; }
.md :deep(.md-pre) { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; overflow: auto; margin: 6px 0; }
.md :deep(.md-pre code) { font-family: var(--mono); font-size: 12px; white-space: pre; background: none; border: 0; padding: 0; }
.md :deep(a) { color: var(--warp-hi); text-decoration: underline; }
.md :deep(.md-tablewrap) { overflow-x: auto; margin: 8px 0; }
.md :deep(.md-table) { border-collapse: collapse; font-size: 12.5px; }
.md :deep(.md-table th), .md :deep(.md-table td) { border: 1px solid var(--line); padding: 5px 9px; text-align: left; vertical-align: top; }
.md :deep(.md-table th) { background: var(--chip-bg); color: var(--ink); font-weight: 600; }
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
