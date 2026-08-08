<script setup lang="ts">
// Evidence primitive: anything machine-derived/verifiable renders here (DESIGN.md §1).
// Carries an SR-only "verified data" cue so the epistemic split isn't font-only.
withDefaults(defineProps<{ label?: boolean }>(), { label: false })
</script>

<template>
  <span :class="['mono', { eyebrow: label }]"><span class="sr-only">verified data: </span><slot /></span>
</template>
