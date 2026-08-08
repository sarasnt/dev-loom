<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { Integration } from '../types'
import { fetchIntegrations } from '../api/stub'

const items = ref<Integration[]>([])
const loading = ref(true)
onMounted(async () => {
  items.value = await fetchIntegrations()
  loading.value = false
})
const dotClass = (s: Integration['state']) =>
  s === 'connected' ? 'healthy' : s === 'partial' ? 'warn' : 'off'
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Integrations</h1>
      <span class="when">DevLoom reads these to build your view — it writes nothing back.</span>
    </div>

    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else>
      <section v-for="it in items" :key="it.key" class="prov" :class="{ dashed: it.state === 'not_connected' }">
        <div class="ph">
          <span class="dot" :class="dotClass(it.state)" aria-hidden="true"></span>
          <h3>{{ it.name }}</h3>
          <span v-if="it.state === 'partial'" class="pstate mono">◑ {{ it.detail }}</span>
          <span v-else-if="it.state === 'connected'" class="tag ok mono">● {{ it.detail }}</span>
          <button v-else class="btn pri push">{{ it.actions[0] }} ▸</button>
        </div>
        <div v-if="it.scopes" class="scopes mono">scopes (read): {{ it.scopes.join(' · ') }}</div>
        <p v-if="it.note" class="note">{{ it.note }}</p>
        <div v-if="it.state !== 'not_connected'" class="acts">
          <button v-for="a in it.actions" :key="a" class="btn" :class="{ ghost: a === 'Disconnect' }">{{ a }}</button>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; }
.head h1 { font-size: 22px; }
.when { font-size: 12px; color: var(--faint-text); }
.empty { color: var(--faint-text); padding: 20px 0; }
.prov { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.prov.dashed { border-style: dashed; }
.ph { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ph h3 { font-size: 15px; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.healthy { background: var(--healthy); }
.dot.warn { background: var(--warp); }
.dot.off { background: var(--faint); }
.pstate { margin-left: auto; color: var(--warp-hi); font-size: 12px; }
.tag { margin-left: auto; font-size: 11px; }
.tag.ok { color: var(--healthy); border: 1px solid var(--healthy); border-radius: 5px; padding: 2px 6px; }
.push { margin-left: auto; }
.scopes { color: var(--faint-text); font-size: 11.5px; margin-bottom: 10px; }
.note { color: var(--dim); font-size: 13px; margin: 0 0 10px; }
.acts { display: flex; gap: 8px; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); }
.btn:hover { border-color: var(--warp); }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
</style>
