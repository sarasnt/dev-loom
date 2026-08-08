<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { Handoff } from '../types'
import { fetchHandoff } from '../api/stub'
import SourceChip from '../components/SourceChip.vue'

const route = useRoute()
const data = ref<Handoff | null>(null)
const loading = ref(true)
const copied = ref(false)

onMounted(async () => {
  data.value = await fetchHandoff(String(route.params.id ?? 'h1'))
  loading.value = false
})

async function copy() {
  if (!data.value) return
  try {
    await navigator.clipboard.writeText(data.value.rendered)
    copied.value = true
    setTimeout(() => (copied.value = false), 1800)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <main class="wrap">
    <div v-if="loading" class="mono empty">preparing handoff…</div>
    <template v-else-if="data">
      <div class="head">
        <h1>Agent handoff — {{ data.title }}</h1>
        <span class="when mono">target: {{ data.target }} · <span class="local">⌂ local</span></span>
      </div>

      <pre class="artifact mono">{{ data.rendered }}</pre>

      <section class="safety">
        <div class="st mono">Safety — locked defaults, editable</div>
        <div v-for="a in data.safety.allow" :key="a" class="yes mono">✓ {{ a }}</div>
        <div v-for="f in data.safety.forbid" :key="f" class="no mono">✗ {{ f }}</div>
      </section>

      <div class="footer">
        <div class="chips">
          <SourceChip v-for="s in data.sources" :key="s.id" :ref-item="s" />
        </div>
        <div class="actions">
          <button class="btn pri" @click="copy">{{ copied ? 'Copied ✓' : 'Copy' }}</button>
          <button class="btn">Export .md</button>
          <button class="btn ghost" disabled title="Direct agent execution is a future, approval-gated capability">
            Run ▸
          </button>
        </div>
      </div>
    </template>
  </main>
</template>

<style scoped>
.wrap { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16px; }
.head h1 { font-size: 22px; }
.when { font-size: 12px; color: var(--faint-text); }
.when .local { color: var(--warp-hi); }
.empty { color: var(--faint-text); padding: 24px 0; }
.artifact {
  font-size: 12.5px; line-height: 1.7; background: var(--bg); border: 1px solid var(--line);
  border-radius: var(--r-card); padding: 16px; color: var(--dim); white-space: pre-wrap; margin: 0;
}
.safety { border: 1px solid var(--warp); border-radius: 8px; padding: 12px 14px; margin-top: 12px; background: var(--warp-weft); }
.st { font-size: 11px; letter-spacing: 0.12em; color: var(--warp-hi); text-transform: uppercase; margin-bottom: 8px; }
.safety div { font-size: 12.5px; padding: 2px 0; }
.yes { color: var(--healthy); }
.no { color: var(--chip-fail); }
.footer { display: flex; align-items: center; gap: 10px; margin-top: 14px; }
.chips { display: flex; flex-wrap: wrap; gap: 7px; }
.actions { margin-left: auto; display: flex; gap: 8px; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); }
.btn:hover { border-color: var(--warp); }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; opacity: 0.5; cursor: not-allowed; }
</style>
