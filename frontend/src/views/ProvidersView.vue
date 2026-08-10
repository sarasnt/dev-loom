<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { ProvidersData, KeyProvider } from '../types'
import {
  fetchProviders, setProviderKey, clearProviderKey, fetchSettings, saveTerminalWorkdir,
} from '../api'
import { useDashboardStore } from '../stores/dashboard'
import SettingsTabs from '../components/SettingsTabs.vue'

const store = useDashboardStore()
const data = ref<ProvidersData | null>(null)
const loading = ref(true)
const draft = ref<Record<string, string>>({ anthropic: '', openai: '' })
const busy = ref('')
const flash = ref('')
const workdir = ref('')
const savingWd = ref(false)

onMounted(async () => {
  data.value = await fetchProviders()
  try { workdir.value = (await fetchSettings()).terminalWorkdir } catch { /* ignore */ }
  loading.value = false
})

async function saveWorkdir() {
  if (savingWd.value) return
  savingWd.value = true
  flash.value = ''
  try {
    workdir.value = (await saveTerminalWorkdir(workdir.value.trim())).terminalWorkdir
    flash.value = workdir.value
      ? 'Terminal working directory saved.'
      : 'Terminal working directory cleared (uses your home folder).'
  } catch {
    flash.value = 'Could not save the terminal working directory.'
  } finally {
    savingWd.value = false
  }
}

async function saveKey(provider: string) {
  const key = (draft.value[provider] || '').trim()
  if (!key || busy.value) return
  busy.value = provider
  flash.value = ''
  try {
    data.value = await setProviderKey(provider, key)
    draft.value[provider] = ''
    flash.value = `${provider} key saved (encrypted).`
    await store.ensureLoaded() // remote models now selectable
  } catch {
    flash.value = `Could not save the ${provider} key.`
  } finally {
    busy.value = ''
  }
}

async function removeKey(provider: string) {
  if (busy.value || !confirm(`Remove the ${provider} API key?`)) return
  busy.value = provider
  try {
    data.value = await clearProviderKey(provider)
    flash.value = `${provider} key removed.`
    await store.ensureLoaded()
  } finally {
    busy.value = ''
  }
}

const dollars = (c?: number | null) => (c == null ? '' : `$${(c / 100).toFixed(2)}`)
const usedPct = (p: KeyProvider) =>
  p.capCents ? Math.min(100, Math.round((100 * (p.usedCents ?? 0)) / p.capCents)) : 0
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head"><h1>Model providers</h1></div>
    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else-if="data">
      <div v-if="flash" class="flash mono">{{ flash }}</div>
      <div v-if="!data.canStoreKeys" class="warnbar mono">
        Set <b>DEVLOOM_SECRET</b> in backend/.env to store API keys encrypted in-app.
      </div>

      <!-- local -->
      <section class="prov">
        <div class="ph">
          <span class="dot healthy" aria-hidden="true"></span>
          <h3>Local · {{ data.local.name }}</h3>
          <span class="boundary local mono"><span aria-hidden="true">⌂</span> nothing leaves</span>
        </div>
        <div class="row">
          <span class="mono lbl">active</span>
          <span class="select mono">{{ data.local.active }}</span>
          <span v-if="data.local.loaded" class="tag ok mono">● loaded</span>
        </div>
        <div v-if="data.local.models.length" class="avail mono">
          pulled: {{ data.local.models.join(' · ') }}
        </div>
      </section>

      <!-- keyed providers -->
      <section v-for="p in [data.anthropic, data.openai]" :key="p.key" class="prov" :class="{ muted: !p.hasKey }">
        <div class="ph">
          <span class="dot" :class="p.hasKey ? 'healthy' : 'off'" aria-hidden="true"></span>
          <h3>{{ p.name }} <span class="opt mono">· optional · your key</span></h3>
          <span class="boundary remote mono"><span aria-hidden="true">◉</span> {{ p.boundaryLabel }}</span>
        </div>

        <!-- has a key -->
        <template v-if="p.hasKey">
          <div class="row">
            <span class="mono lbl">key</span>
            <span class="mono keyhint">{{ p.maskedKey }}</span>
            <span v-if="p.valid" class="tag ok mono">✓ set</span>
            <button class="btn ghost" :disabled="busy === p.key" @click="removeKey(p.key)">Remove</button>
          </div>
          <div v-if="p.capCents" class="row">
            <span class="mono lbl">cap {{ dollars(p.capCents) }} · used</span>
            <span class="meter"><i :style="{ width: usedPct(p) + '%' }"></i></span>
            <span class="mono">{{ dollars(p.usedCents) }}</span>
          </div>
          <div v-if="p.models?.length" class="avail mono">models: {{ p.models.join(' · ') }}</div>
        </template>

        <!-- no key yet -->
        <template v-else>
          <div class="row">
            <input
              v-model="draft[p.key]"
              class="keyin mono"
              type="password"
              :placeholder="p.key === 'anthropic' ? 'sk-ant-…' : 'sk-…'"
              :disabled="!data.canStoreKeys || busy === p.key"
              autocomplete="off"
              @keydown.enter="saveKey(p.key)"
            />
            <button class="btn pri" :disabled="!data.canStoreKeys || busy === p.key || !draft[p.key]" @click="saveKey(p.key)">
              Save key
            </button>
          </div>
          <div class="avail mono">Adds {{ p.models?.join(' · ') }} to your model picker.</div>
        </template>
      </section>

      <!-- claude-cli terminal working directory -->
      <section class="prov">
        <div class="ph">
          <span class="dot healthy" aria-hidden="true"></span>
          <h3>Claude Code terminal <span class="opt mono">· claude-cli</span></h3>
        </div>
        <div class="row">
          <span class="mono lbl">start in</span>
          <input
            v-model="workdir"
            class="keyin mono"
            type="text"
            placeholder="e.g. C:\Users\you\Documents\projects  (blank = home folder)"
            :disabled="savingWd"
            @keydown.enter="saveWorkdir"
          />
          <button class="btn pri" :disabled="savingWd" @click="saveWorkdir">Save</button>
        </div>
        <div class="avail mono">
          Where a non-repo claude-cli terminal opens. Pick a folder you trust so Claude Code
          stops asking on every session. Repo-scoped brainstorms still open in their repo.
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head h1 { font-size: 22px; margin-bottom: 18px; }
.empty { color: var(--faint-text); padding: 20px 0; }
.flash { font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.warnbar { font-size: 12.5px; color: var(--dim); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.prov { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.prov.muted { opacity: 0.9; }
.ph { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ph h3 { font-size: 15px; }
.opt { color: var(--faint-text); font-size: 11px; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.healthy { background: var(--healthy); }
.dot.off { background: var(--faint); }
.boundary { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; font-size: 11px; border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; }
.boundary.local { color: var(--dim); }
.boundary.local span { color: var(--healthy); }
.boundary.remote { border-color: var(--warp); color: var(--warp-hi); }
.boundary.remote span { color: var(--warp); }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.lbl { color: var(--dim); font-size: 12px; }
.keyhint { color: var(--warp-hi); }
.keyin { flex: 1; max-width: 360px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; color: var(--ink); font-size: 12px; }
.keyin:focus { outline: none; border-color: var(--warp); }
.select { border: 1px solid var(--line); border-radius: 6px; padding: 5px 9px; background: var(--chip-bg); color: var(--ink); font-size: 12px; }
.tag.ok { color: var(--healthy); border: 1px solid var(--healthy); border-radius: 5px; padding: 2px 6px; font-size: 10px; }
.avail { color: var(--faint-text); font-size: 11.5px; }
.meter { height: 8px; border-radius: 5px; background: var(--raised); overflow: hidden; width: 160px; }
.meter i { display: block; height: 100%; background: var(--warp); }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 5px 10px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
</style>
