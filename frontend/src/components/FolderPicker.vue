<script setup lang="ts">
// A folder browser over the host agent's filesystem.
//
// Extracted from Settings › General when its settings were split across two tabs: three different
// blocks opened this same modal, and they no longer live on the same page. Keeping one copy also
// keeps one behaviour — "open a folder, then use it" — rather than two that drift.
import { ref } from 'vue'
import type { BrowseResult } from '../types'
import { browseFs } from '../api'

const emit = defineEmits<{ picked: [path: string]; error: [message: string] }>()

const open = ref(false)
const data = ref<BrowseResult | null>(null)
const loading = ref(false)

async function show(startAt?: string) {
  open.value = true
  data.value = null
  await navigate(startAt ?? '')
}

async function navigate(p: string) {
  loading.value = true
  try {
    data.value = await browseFs(p)
  } catch {
    emit('error', 'Browse failed — is the host agent running?')
    open.value = false
  } finally {
    loading.value = false
  }
}

function useFolder() {
  if (!data.value) return
  const picked = data.value.path
  open.value = false
  emit('picked', picked)
}

// The parent opens it; everything else is this component's business.
defineExpose({ show })
</script>

<template>
  <div v-if="open" class="modal" @click.self="open = false">
    <div class="picker">
      <div class="pkhead">
        <span class="mono pkpath">{{ data?.path || '…' }}</span>
        <button class="btn ghost" @click="open = false">✕</button>
      </div>
      <div class="pklist">
        <button v-if="data?.parent" class="pkrow up" @click="navigate(data.parent!)">⤴ ..</button>
        <button v-for="d in data?.drives ?? []" :key="d.path" class="pkrow" @click="navigate(d.path)">🖴 {{ d.name }}</button>
        <button v-for="d in data?.dirs ?? []" :key="d.path" class="pkrow" @click="navigate(d.path)">📁 {{ d.name }}</button>
        <div v-if="loading" class="mono clean">…</div>
      </div>
      <div class="pkfoot">
        <span class="mono hint">Open a folder, then use it.</span>
        <button class="btn pri" :disabled="!data?.path" @click="useFolder">Use this folder</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal { position: fixed; inset: 0; background: rgba(0,0,0,0.55); display: flex; align-items: center; justify-content: center; z-index: 50; }
.picker { width: min(560px, 92vw); max-height: 70vh; display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-card); }
.pkhead { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.pkpath { flex: 1; font-size: 12px; color: var(--dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pklist { flex: 1; overflow: auto; padding: 8px; }
.pkrow { display: block; width: 100%; text-align: left; background: transparent; border: 0; color: var(--ink); font-size: 13px; padding: 7px 10px; border-radius: 6px; cursor: pointer; }
.pkrow:hover { background: var(--chip-bg); }
.pkrow.up { color: var(--dim); }
.pkfoot { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 12px 14px; border-top: 1px solid var(--line); }
.hint { font-size: 11px; color: var(--faint-text); }
.btn { font-size: 12.5px; padding: 7px 12px; border-radius: 8px; border: 1px solid var(--line); background: var(--surface); color: var(--ink); cursor: pointer; }
.btn.pri { border-color: var(--warp); color: var(--warp-hi); }
.btn.ghost { border-color: transparent; color: var(--dim); }
.btn:disabled { opacity: 0.5; cursor: default; }
.clean { color: var(--faint-text); padding: 8px 10px; font-size: 12px; }
</style>
