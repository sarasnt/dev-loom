<script setup lang="ts">
// Per-screen model selector, bounded to one screen. The choice persists per screen (so
// Brainstorm and Handoffs can differ) and is passed to the backend per request. Emits the
// chosen model; use v-model or listen to @change.
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useDashboardStore } from '../stores/dashboard'
import { isRemoteModel } from '../utils/models'

const props = defineProps<{
  screen: string
  label?: string
  disabled?: boolean
  includeAgent?: boolean // claude-code / claude-cli — only Brainstorm should offer these
  localOnly?: boolean // restrict to local models only (a local-only repo never uses remote)
  manual?: boolean // don't persist on change; let the parent decide (Brainstorm boundary rules)
  modelValue?: string // optional override (e.g. a per-session model in Brainstorm)
}>()
const emit = defineEmits<{ (e: 'update:modelValue', name: string): void; (e: 'change', name: string): void }>()

const store = useDashboardStore()
const { models, agentModels } = storeToRefs(store)

// The agent (subscription) modes are exclusive to Brainstorm; every other screen hides them.
// A local-only context strips ALL remote models (agent modes leave the machine too).
const options = computed(() => {
  let list = models.value
  if (props.localOnly) return list.filter((m) => !isRemoteModel(m))
  return props.includeAgent ? list : list.filter((m) => !agentModels.value.includes(m))
})
const current = computed(() => props.modelValue ?? store.modelFor(props.screen))
const remote = computed(() => isRemoteModel(current.value))

function onChange(e: Event) {
  const name = (e.target as HTMLSelectElement).value
  if (!props.manual) store.setModelFor(props.screen, name)
  emit('update:modelValue', name)
  emit('change', name)
}
</script>

<template>
  <label class="ms">
    <span v-if="label" class="mslab mono">{{ label }}</span>
    <select class="msel mono" :value="current" :disabled="disabled" aria-label="Model" @change="onChange">
      <option v-for="m in options" :key="m" :value="m">{{ m }}</option>
    </select>
    <span class="tag mono" :class="{ remote }">· {{ remote ? 'remote' : 'local' }}</span>
  </label>
</template>

<style scoped>
.ms { display: inline-flex; align-items: center; gap: 8px; }
.mslab { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--faint-text); }
.msel {
  background: var(--chip-bg); border: 1px solid var(--line); border-radius: 6px;
  padding: 5px 9px; color: var(--ink); font-size: 12px; cursor: pointer; max-width: 210px;
}
.msel:hover { border-color: var(--warp); }
.msel:focus { outline: none; border-color: var(--warp); }
.msel:disabled { opacity: 0.6; cursor: not-allowed; }
.tag { font-size: 11px; color: var(--healthy); }
.tag.remote { color: var(--warp-hi); }
</style>
