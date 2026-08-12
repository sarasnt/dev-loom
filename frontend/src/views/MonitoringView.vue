<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { MonitoringData, PrivacyData } from '../types'
import { fetchMonitoring, fetchPrivacy, setMonitoringRetention } from '../api'
import SettingsTabs from '../components/SettingsTabs.vue'

const data = ref<MonitoringData | null>(null)
const privacy = ref<PrivacyData | null>(null)
const loading = ref(true)
let timer: number | undefined

// How far back the figures cover. Kept client-side (like the screen model choices) — it's a way of
// looking at the history, not a setting the backend should remember.
const WINDOWS = [
  { days: 1, label: '24h' },
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 0, label: 'All' },
]
const windowDays = ref<number>(Number(localStorage.getItem('devloom.monitoringWindow') ?? 7))

function setWindow(days: number) {
  windowDays.value = days
  localStorage.setItem('devloom.monitoringWindow', String(days))
  load()
}

async function load() {
  data.value = await fetchMonitoring(windowDays.value)
  try { privacy.value = await fetchPrivacy() } catch { /* egress stays hidden */ }
  loading.value = false
}

// Retention, unlike the view window, is a real setting — it decides what gets deleted.
const RETENTIONS = [7, 30, 90, 365, 0]
const savingRetention = ref(false)
async function changeRetention(days: number) {
  savingRetention.value = true
  try {
    await setMonitoringRetention(days)
    await load() // pruning runs on save, so the figures may legitimately shrink
  } finally { savingRetention.value = false }
}
onMounted(() => {
  load()
  timer = window.setInterval(load, 4000) // live: every model call shows up within ~4s
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

function ago(ts: number): string {
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000))
  if (s < 60) return `${s}s ago`
  const m = Math.round(s / 60)
  return m < 60 ? `${m}m ago` : `${Math.round(m / 60)}h ago`
}
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head">
      <h1>Model monitoring</h1>
      <div class="windows mono">
        <button v-for="w in WINDOWS" :key="w.days" class="chip"
                :class="{ on: windowDays === w.days }" @click="setWindow(w.days)">{{ w.label }}</button>
      </div>
      <button class="chip" @click="load">↻ Refresh</button>
    </div>
    <p class="sub">
      Every model call routes through LangChain4j, so latency and token usage below are measured, not estimated.
      Local (Ollama) and remote (Anthropic/OpenAI) calls are all observed by one listener.
      History is stored and survives restarts.
    </p>

    <div v-if="data" class="retention mono">
      <span class="rlab">Keep history for</span>
      <select class="rsel" :value="data.retentionDays" :disabled="savingRetention"
              @change="changeRetention(Number(($event.target as HTMLSelectElement).value))">
        <option v-for="d in RETENTIONS" :key="d" :value="d">{{ d === 0 ? 'forever' : `${d} days` }}</option>
      </select>
      <span class="rnote">older calls are deleted hourly</span>
    </div>

    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else-if="data">
      <section class="totals">
        <div class="stat"><div class="n">{{ data.totals.calls }}</div><div class="l mono">calls</div></div>
        <div class="stat"><div class="n">{{ data.totals.errors }}</div><div class="l mono">errors</div></div>
        <div class="stat"><div class="n">{{ data.totals.inputTokens.toLocaleString() }}</div><div class="l mono">input tokens</div></div>
        <div class="stat"><div class="n">{{ data.totals.outputTokens.toLocaleString() }}</div><div class="l mono">output tokens</div></div>
      </section>

      <section class="block">
        <div class="lab mono">Per model</div>
        <div v-if="!data.models.length" class="empty mono">
          No calls yet — run a Brainstorm or a build analysis, then come back (this refreshes live).
        </div>
        <table v-else class="tbl">
          <thead>
            <tr><th>Provider</th><th>Model</th><th class="r">Calls</th><th class="r">Errors</th><th class="r">Avg latency</th><th class="r">In</th><th class="r">Out</th></tr>
          </thead>
          <tbody>
            <tr v-for="m in data.models" :key="m.provider + '/' + m.model">
              <td><span class="badge" :class="m.provider === 'ollama' ? 'local' : 'remote'">{{ m.provider }}</span></td>
              <td class="mono">{{ m.model }}</td>
              <td class="r">{{ m.calls }}</td>
              <td class="r" :class="{ bad: m.errors > 0 }">{{ m.errors }}</td>
              <td class="r">{{ m.avgLatencyMs }} ms</td>
              <td class="r">{{ m.inputTokens.toLocaleString() }}</td>
              <td class="r">{{ m.outputTokens.toLocaleString() }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="block">
        <div class="lab mono">Recent calls</div>
        <div v-if="!data.recent.length" class="empty mono">Nothing yet.</div>
        <div v-for="(c, i) in data.recent" :key="i" class="call mono" :class="{ err: !c.ok }">
          <span class="t">{{ ago(c.at) }}</span>
          <span class="badge" :class="c.provider === 'ollama' ? 'local' : 'remote'">{{ c.provider }}</span>
          <span class="mdl">{{ c.model }}</span>
          <span class="lat">{{ c.latencyMs }} ms</span>
          <span class="tok">{{ c.inputTokens }}→{{ c.outputTokens }} tok</span>
          <span v-if="!c.ok" class="msg">✕ {{ c.error }}</span>
        </div>
      </section>

      <section v-if="privacy" class="block">
        <div class="lab mono">Egress log <span class="hintl">(what left your machine, when, to whom)</span></div>
        <div v-for="(e, i) in privacy.egress" :key="i" class="call mono">
          <span class="t">{{ e.time }}</span>
          <span class="mdl">{{ e.action }}<template v-if="e.to"> → {{ e.to }}</template></span>
          <span class="tok">{{ e.tokens }}</span>
        </div>
        <p class="foot" style="margin-top: 8px">
          {{ privacy.defaultBoundary }} Local-only repos are managed per repo on the Repositories page.
        </p>
      </section>

      <p class="foot">
        Same data is exported as Micrometer meters at
        <code>{{ data.metricsPath }}</code>.
        <template v-if="data.langfuseEnabled">
          Langfuse export is <strong>on</strong> — open <a href="http://localhost:3000" target="_blank" rel="noopener">Langfuse</a>.
        </template>
        <template v-else>
          For a full trace UI, start Langfuse (<code>docker compose --profile obs up -d</code> → <a href="http://localhost:3000" target="_blank" rel="noopener">localhost:3000</a>)
          and set <code>DEVLOOM_LANGFUSE_ENABLED=true</code>.
        </template>
      </p>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.head h1 { font-size: 22px; margin-right: auto; }
.windows { display: flex; gap: 6px; }
.retention { display: flex; align-items: center; gap: 10px; margin: 0 0 14px; font-size: 12px; color: var(--dim); }
.rsel { background: var(--bg); border: 1px solid var(--line); border-radius: 6px; color: var(--ink); padding: 4px 8px; font-family: var(--mono); font-size: 12px; }
.rnote { color: var(--faint-text); }
.sub { color: var(--dim); font-size: 13px; margin: 0 0 18px; max-width: 70ch; }
.empty { color: var(--faint-text); padding: 12px 0; }
.chip { font-size: 12px; color: var(--dim); border: 1px solid var(--line); background: var(--surface); border-radius: 20px; padding: 5px 12px; cursor: pointer; }
.chip:hover { color: var(--ink); }
.chip.on { color: var(--warp-hi); border-color: var(--warp); }
.totals { display: flex; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.stat { flex: 1; min-width: 120px; border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 14px 16px; }
.stat .n { font-size: 26px; font-variant-numeric: tabular-nums; }
.stat .l { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--faint-text); margin-top: 4px; }
.block { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.lab { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 10px; }
.hintl { text-transform: none; letter-spacing: 0; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); padding: 6px 10px; border-bottom: 1px solid var(--line); }
.tbl td { padding: 8px 10px; border-bottom: 1px solid var(--line); color: var(--ink); }
.tbl tr:last-child td { border-bottom: none; }
.r { text-align: right; font-variant-numeric: tabular-nums; }
.bad { color: var(--danger, #d66); }
.badge { font-size: 10.5px; padding: 2px 7px; border-radius: 20px; text-transform: uppercase; letter-spacing: 0.06em; }
.badge.local { background: color-mix(in srgb, var(--healthy) 18%, transparent); color: var(--healthy); }
.badge.remote { background: color-mix(in srgb, var(--warp-hi) 18%, transparent); color: var(--warp-hi); }
.call { display: flex; gap: 12px; align-items: center; font-size: 12px; color: var(--dim); padding: 5px 0; border-bottom: 1px solid color-mix(in srgb, var(--line) 55%, transparent); }
.call:last-child { border-bottom: none; }
.call.err { color: var(--danger, #d66); }
.call .t { color: var(--faint-text); width: 64px; }
.call .mdl { color: var(--ink); }
.call .lat { margin-left: auto; color: var(--faint-text); }
.call .tok { color: var(--faint-text); width: 120px; text-align: right; }
.call .msg { color: var(--danger, #d66); }
.foot { color: var(--faint-text); font-size: 12.5px; margin-top: 4px; line-height: 1.7; }
.foot code { background: var(--surface); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; font-size: 11.5px; }
.foot a { color: var(--warp-hi); }
</style>
