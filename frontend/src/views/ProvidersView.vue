<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import type { ProvidersData } from '../types'
import { fetchProviders } from '../api/stub'

const data = ref<ProvidersData | null>(null)
const loading = ref(true)
onMounted(async () => {
  data.value = await fetchProviders()
  loading.value = false
})
const usedPct = computed(() => {
  if (!data.value?.anthropic.capCents) return 0
  return Math.round((100 * (data.value.anthropic.usedCents ?? 0)) / data.value.anthropic.capCents)
})
const dollars = (c?: number) => (c == null ? '' : `$${(c / 100).toFixed(2)}`)
</script>

<template>
  <main class="main">
    <div class="head"><h1>Model providers</h1></div>
    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else-if="data">
      <!-- local -->
      <section class="prov">
        <div class="ph">
          <span class="dot healthy" aria-hidden="true"></span>
          <h3>Local · {{ data.local.name }}</h3>
          <span class="boundary local mono"><span aria-hidden="true">⌂</span> nothing leaves</span>
        </div>
        <div class="row">
          <span class="mono lbl">default</span>
          <span class="select mono">{{ data.local.defaultModel }} ▾</span>
          <span v-if="data.local.loaded" class="tag ok mono">● loaded</span>
        </div>
        <div class="avail mono">available: {{ data.local.models.slice(1).join(' · ') }} · + pull</div>
      </section>

      <!-- anthropic -->
      <section class="prov">
        <div class="ph">
          <span class="dot warn" aria-hidden="true"></span>
          <h3>Anthropic <span class="opt mono">· optional · your key</span></h3>
          <span class="boundary remote mono"><span aria-hidden="true">◉</span> {{ data.anthropic.boundaryLabel }}</span>
        </div>
        <div class="row">
          <span class="mono lbl">key ••••••</span>
          <button class="btn">Test</button>
          <span v-if="data.anthropic.valid" class="tag ok mono">✓ valid</span>
        </div>
        <div class="row" v-if="data.anthropic.capCents">
          <span class="mono lbl">cap {{ dollars(data.anthropic.capCents) }} · used</span>
          <span class="meter"><i :style="{ width: usedPct + '%' }"></i></span>
          <span class="mono">{{ dollars(data.anthropic.usedCents) }}</span>
        </div>
      </section>

      <!-- openai -->
      <section class="prov muted">
        <div class="ph">
          <span class="dot off" aria-hidden="true"></span>
          <h3>OpenAI <span class="opt mono">· optional · your key</span></h3>
          <button class="btn ghost push">+ Add key</button>
        </div>
        <div class="avail mono">{{ data.openai.note }}</div>
      </section>

      <!-- fallback -->
      <section class="prov dashed">
        <div class="ph">
          <span class="off-glyph" aria-hidden="true">○</span>
          <h3>Fallback</h3>
          <span class="push muted-txt">off by default</span>
        </div>
        <p class="note">If enabled, you choose <b>exactly</b> when to fall back to a paid model — never silent.</p>
      </section>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head h1 { font-size: 22px; margin-bottom: 18px; }
.empty { color: var(--faint-text); padding: 20px 0; }
.prov { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.prov.dashed { border-style: dashed; }
.prov.muted { opacity: 0.85; }
.ph { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ph h3 { font-size: 15px; }
.opt { color: var(--faint-text); font-size: 11px; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.healthy { background: var(--healthy); }
.dot.warn { background: var(--warp); }
.dot.off { background: var(--faint); }
.off-glyph { color: var(--faint-text); }
.boundary { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; font-size: 11px; border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; }
.boundary.local { color: var(--dim); }
.boundary.local span { color: var(--healthy); }
.boundary.remote { border-color: var(--warp); color: var(--warp-hi); }
.boundary.remote span { color: var(--warp); }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.lbl { color: var(--dim); font-size: 12px; }
.select { border: 1px solid var(--line); border-radius: 6px; padding: 5px 9px; background: var(--chip-bg); color: var(--ink); font-size: 12px; }
.tag.ok { color: var(--healthy); border: 1px solid var(--healthy); border-radius: 5px; padding: 2px 6px; font-size: 10px; }
.avail { color: var(--faint-text); font-size: 11.5px; }
.meter { height: 8px; border-radius: 5px; background: var(--raised); overflow: hidden; width: 160px; }
.meter i { display: block; height: 100%; background: var(--warp); }
.push { margin-left: auto; }
.muted-txt { color: var(--faint-text); font-size: 12px; }
.note { color: var(--dim); font-size: 13px; margin: 0; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 5px 10px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); }
.btn:hover { border-color: var(--warp); }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
</style>
