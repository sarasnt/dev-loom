<script setup lang="ts">
// Per-screen model selector, bounded to one screen. A searchable combobox (type to filter by
// partial match — handy now that keyed providers expose dozens of models). The choice persists
// per screen and is passed to the backend per request.
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
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

// Agent (subscription) modes are exclusive to Brainstorm; a local-only context strips ALL
// remote models (agent modes leave the machine too).
const options = computed(() => {
  const list = models.value
  if (props.localOnly) return list.filter((m) => !isRemoteModel(m))
  return props.includeAgent ? list : list.filter((m) => !agentModels.value.includes(m))
})

const current = computed(() => props.modelValue ?? store.modelFor(props.screen))
const remote = computed(() => isRemoteModel(current.value))

const root = ref<HTMLElement | null>(null)
const open = ref(false)
const query = ref('')
const hi = ref(0)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  return q ? options.value.filter((m) => m.toLowerCase().includes(q)) : options.value
})

function onFocus() {
  if (props.disabled) return
  open.value = true
  query.value = ''
  hi.value = 0
}
function onInput(e: Event) {
  query.value = (e.target as HTMLInputElement).value
  open.value = true
  hi.value = 0
}
function move(d: number) {
  if (!open.value) { open.value = true; return }
  const n = filtered.value.length
  if (n) hi.value = (hi.value + d + n) % n
}
function choose(m?: string) {
  if (!m) return
  if (!props.manual) store.setModelFor(props.screen, m)
  emit('update:modelValue', m)
  emit('change', m)
  open.value = false
  query.value = ''
}
function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('mousedown', onDocClick))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClick))
</script>

<template>
  <div class="ms" ref="root">
    <span v-if="label" class="mslab mono">{{ label }}</span>
    <div class="combo" :class="{ open, disabled }">
      <input
        class="cinput mono"
        :value="open ? query : current"
        :placeholder="current || 'model'"
        :disabled="disabled"
        aria-label="Model"
        spellcheck="false"
        @focus="onFocus"
        @input="onInput"
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
        @keydown.enter.prevent="choose(filtered[hi])"
        @keydown.esc="open = false"
      />
      <span class="tag mono" :class="{ remote }">· {{ remote ? 'remote' : 'local' }}</span>
      <div v-if="open" class="cmenu">
        <button
          v-for="(m, idx) in filtered"
          :key="m"
          class="citem mono"
          :class="{ hi: idx === hi, cur: m === current }"
          @mousedown.prevent="choose(m)"
          @mouseenter="hi = idx"
        >{{ m }}</button>
        <div v-if="!filtered.length" class="citem empty mono">no match</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ms { display: inline-flex; align-items: center; gap: 8px; }
.mslab { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--faint-text); }
/* Wraps and stays inside its container: in Brainstorm's rail the model name plus the boundary tag
   ran 9px past the edge, which put a horizontal scrollbar across the whole app. */
.combo { position: relative; display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; max-width: 100%; }
.cinput {
  background: var(--chip-bg); border: 1px solid var(--line); border-radius: 6px;
  padding: 5px 9px; color: var(--ink); font-size: 12px; width: 190px; cursor: text;
}
.cinput:hover { border-color: var(--warp); }
.cinput:focus { outline: none; border-color: var(--warp); }
.cinput:disabled { opacity: 0.6; cursor: not-allowed; }
.tag { font-size: 11px; color: var(--healthy); white-space: nowrap; }
.tag.remote { color: var(--warp-hi); }
.cmenu {
  position: absolute; top: calc(100% + 4px); left: 0; z-index: 40; min-width: 220px; max-width: 320px;
  max-height: 280px; overflow-y: auto; background: var(--surface); border: 1px solid var(--line);
  border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,0.35);
}
.citem {
  display: block; width: 100%; text-align: left; background: transparent; border: 0;
  border-radius: 6px; padding: 6px 9px; color: var(--ink); font-size: 12px; cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.citem.hi { background: var(--nav-hover); }
.citem.cur { color: var(--warp-hi); }
.citem.empty { color: var(--faint-text); cursor: default; }
</style>
