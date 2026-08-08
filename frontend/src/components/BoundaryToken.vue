<script setup lang="ts">
import type { Boundary } from '../types'
defineProps<{ boundary: Boundary; compact?: boolean }>()
const glyph = (m: string) => (m === 'local' ? '⌂' : m === 'remote' ? '◉' : '◐')
</script>

<template>
  <span :class="['boundary', boundary.mode, { compact }]">
    <span class="glyph" aria-hidden="true">{{ glyph(boundary.mode) }}</span>
    <span>{{ boundary.label }}</span>
  </span>
</template>

<style scoped>
.boundary {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 8px; border: 1px solid var(--line); border-radius: 8px;
  font-size: 13px; color: var(--dim);
}
.boundary.compact { padding: 4px 8px; }
.glyph { font-size: 15px; }
.boundary.local .glyph { color: var(--healthy); }
.boundary.remote { border-color: var(--warp); color: var(--warp-hi); }
.boundary.remote .glyph { color: var(--warp); }
.boundary.mixed { border-color: var(--warp); color: var(--warp-hi); }
</style>
