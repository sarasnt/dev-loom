<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useDashboardStore } from '../stores/dashboard'
import type { Integration } from '../types'
import { fetchIntegrations, syncSource, disconnectSource } from '../api'
import LoomLoader from '../components/LoomLoader.vue'

const store = useDashboardStore()
const items = ref<Integration[]>([])
const loading = ref(true)
const busy = ref<string>('') // key of the integration currently syncing/disconnecting
const flash = ref<string>('') // transient status line

onMounted(async () => {
  items.value = await fetchIntegrations()
  loading.value = false
})

const dotClass = (s: Integration['state']) =>
  s === 'connected' ? 'healthy' : s === 'partial' ? 'warn' : 'off'

// The backend keys its connectors by source name; the DTO exposes a UI key.
const SOURCE: Record<string, string> = {
  github: 'GitHub',
  jira: 'Jira',
  gcal: 'Calendar',
  notion: 'Notion',
}

async function reload() {
  items.value = await fetchIntegrations()
  await store.ensureLoaded() // keep the rail badge / Today in sync
}

async function onAction(it: Integration, action: string) {
  const source = SOURCE[it.key]
  if (!source) return // e.g. Microsoft "Connect" — not wired in iteration 1
  if (action === 'Re-sync' || action === 'Retry now') {
    busy.value = it.key
    flash.value = ''
    try {
      const r = await syncSource(source)
      flash.value = `${it.name}: synced ${r.ingested} item${r.ingested === 1 ? '' : 's'}.`
      await reload()
    } catch {
      flash.value = `${it.name}: sync failed.`
    } finally {
      busy.value = ''
    }
  } else if (action === 'Disconnect') {
    if (!confirm(`Disconnect ${it.name}? This removes its synced items from DevLoom (your source is untouched).`)) return
    busy.value = it.key
    try {
      const r = await disconnectSource(source)
      flash.value = `${it.name}: disconnected (${r.removed} items removed).`
      await reload()
    } catch {
      flash.value = `${it.name}: disconnect failed.`
    } finally {
      busy.value = ''
    }
  }
}

// Which actions are wired to real behavior right now.
const wired = (a: string) => a === 'Re-sync' || a === 'Retry now' || a === 'Disconnect'
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Integrations</h1>
      <span class="when">DevLoom reads these to build your view — it writes nothing back.</span>
    </div>

    <div v-if="flash" class="flash mono">{{ flash }}</div>

    <div v-if="loading" class="loadwrap"><LoomLoader label="loading integrations…" /></div>
    <template v-else>
      <section v-for="it in items" :key="it.key" class="prov" :class="{ dashed: it.state === 'not_connected' }">
        <div class="ph">
          <span class="dot" :class="dotClass(it.state)" aria-hidden="true"></span>
          <h3>{{ it.name }}</h3>
          <span v-if="busy === it.key" class="pstate mono">◐ working…</span>
          <span v-else-if="it.state === 'partial'" class="pstate mono">◑ {{ it.detail }}</span>
          <span v-else-if="it.state === 'connected'" class="tag ok mono">● {{ it.detail }}</span>
          <button v-else class="btn pri push" disabled title="Connection flow not available in iteration 1">
            {{ it.actions[0] }} ▸
          </button>
        </div>
        <div v-if="it.scopes" class="scopes mono">scopes (read): {{ it.scopes.join(' · ') }}</div>
        <p v-if="it.note" class="note">{{ it.note }}</p>
        <div v-if="it.state !== 'not_connected'" class="acts">
          <button
            v-for="a in it.actions"
            :key="a"
            class="btn"
            :class="{ ghost: a === 'Disconnect' }"
            :disabled="busy === it.key || !wired(a)"
            :title="wired(a) ? '' : 'Not available in iteration 1'"
            @click="onAction(it, a)"
          >
            {{ a }}
          </button>
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
.loadwrap { display: flex; justify-content: center; padding: 56px 0; }
.flash {
  font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp);
  background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px;
}
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn:disabled:hover { border-color: var(--line); }
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
