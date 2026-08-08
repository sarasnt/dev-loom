<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { PrivacyData } from '../types'
import { fetchPrivacy } from '../api'

const data = ref<PrivacyData | null>(null)
const loading = ref(true)
onMounted(async () => {
  data.value = await fetchPrivacy()
  loading.value = false
})
</script>

<template>
  <main class="main">
    <div class="head"><h1>Privacy &amp; data boundary</h1></div>
    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else-if="data">
      <section class="block">
        <div class="lab mono">Default boundary</div>
        <p class="prose"><span class="glyph">⌂</span> {{ data.defaultBoundary }}</p>
      </section>

      <section class="block">
        <div class="lab mono">Local-only repos <span class="hint">(never sent to any remote model)</span></div>
        <div class="repos">
          <span v-for="r in data.localOnlyRepos" :key="r" class="repo mono"><span class="d" aria-hidden="true"></span>{{ r }}</span>
          <span class="chip">+ Mark a repo local-only</span>
        </div>
      </section>

      <section class="block">
        <div class="lab mono">Egress log <span class="hint">(what left, when, to whom)</span></div>
        <div v-for="(e, i) in data.egress" :key="i" class="egress mono">
          <span class="t">{{ e.time }}</span>
          <span class="a">{{ e.action }}<template v-if="e.to"> → {{ e.to }}</template></span>
          <span class="tok">{{ e.tokens }}</span>
          <span v-if="e.to" class="view">[view]</span>
        </div>
      </section>

      <p class="foot">A local-only repo can never leave — its actions route local or are refused, and every remote action is previewed before it runs.</p>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head h1 { font-size: 22px; margin-bottom: 18px; }
.empty { color: var(--faint-text); padding: 20px 0; }
.block { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.lab { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 10px; }
.hint { text-transform: none; letter-spacing: 0; color: var(--faint-text); }
.prose { color: var(--dim); font-size: 14px; margin: 0; }
.prose .glyph { color: var(--healthy); }
.repos { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.repo { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; color: var(--ink); }
.repo .d { width: 8px; height: 8px; border-radius: 50%; background: var(--healthy); display: inline-block; }
.chip { font-size: 12px; color: var(--dim); border: 1px dashed var(--line); border-radius: 20px; padding: 4px 10px; cursor: pointer; }
.egress { display: flex; gap: 14px; align-items: center; font-size: 12px; color: var(--dim); padding: 6px 0; }
.egress .t { color: var(--faint-text); width: 44px; }
.egress .a { color: var(--ink); }
.egress .tok { color: var(--faint-text); margin-left: auto; }
.egress .view { color: var(--warp-hi); }
.foot { color: var(--faint-text); font-size: 12.5px; margin-top: 4px; }
</style>
