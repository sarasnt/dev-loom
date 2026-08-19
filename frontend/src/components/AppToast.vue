<script setup lang="ts">
// Floating rather than inline: the banner this replaces sat above the list with a margin, so
// every message pushed the whole page down and then pulled it back up again.
//
// Errors do not auto-dismiss. A failed push is the one message you actually need to read, and a
// result that removes itself mid-sentence is worse than no result at all.
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{ text: string; tone: 'ok' | 'error' }>()
const emit = defineEmits<{ close: [] }>()

let timer: number | undefined
function arm() {
  if (timer) window.clearTimeout(timer)
  timer = props.tone === 'ok' ? window.setTimeout(() => emit('close'), 4000) : undefined
}
watch(() => [props.text, props.tone], arm, { immediate: true })
onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })
</script>

<template>
  <div class="toast mono" :class="tone" role="status" aria-live="polite">
    <span class="tmsg">{{ text }}</span>
    <button class="tclose" title="Dismiss" @click="emit('close')">✕</button>
  </div>
</template>

<style scoped>
.toast { position: fixed; right: 18px; bottom: 18px; z-index: 60; max-width: 460px; display: flex; align-items: flex-start; gap: 10px; font-size: 12.5px; border-radius: 10px; padding: 10px 12px; white-space: pre-wrap; border: 1px solid var(--warp); background: var(--surface); color: var(--warp-hi); box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35); }
.toast.error { border-color: var(--failed, #a55); color: var(--chip-fail, #d88); }
.tmsg { flex: 1; }
.tclose { background: transparent; border: 0; color: inherit; cursor: pointer; font-size: 12px; line-height: 1.2; padding: 0 2px; }
.tclose:hover { opacity: 0.7; }
</style>
