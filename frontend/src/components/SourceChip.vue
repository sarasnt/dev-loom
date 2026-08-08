<script setup lang="ts">
import type { EvidenceRef } from '../types'
defineProps<{ refItem: EvidenceRef; showBoundary?: boolean }>()
</script>

<template>
  <span class="chip mono">
    <span aria-hidden="true">⧉</span> {{ refItem.id }}
    <span v-if="showBoundary" class="b" :class="refItem.boundary" aria-hidden="true">{{
      refItem.boundary === 'local' ? '⌂' : '◉'
    }}</span>
  </span>
</template>

<style scoped>
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 11px; color: var(--dim); border: 1px solid var(--line);
  border-radius: 5px; padding: 3px 7px; background: var(--chip-bg);
}
.b.local { color: var(--healthy); }
.b.remote { color: var(--warp); }
</style>
