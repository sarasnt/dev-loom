<script setup lang="ts">
// Shared sub-navigation for the Settings screens.
const tabs = [
  { to: '/settings/integrations', label: 'Sources' },
  { to: '/settings/models', label: 'Models' },
  { to: '/settings/capabilities', label: 'Capabilities' },
  { to: '/settings/code', label: 'Repos & runs' },
  { to: '/settings/general', label: 'Workspace' },
  { to: '/settings/monitoring', label: 'Monitoring' },
]
</script>

<template>
  <nav class="stabs" aria-label="Settings sections">
    <RouterLink v-for="t in tabs" :key="t.to" :to="t.to" class="stab" active-class="on">
      {{ t.label }}
    </RouterLink>
  </nav>
</template>

<style scoped>
.stabs { display: flex; gap: 8px; margin-bottom: 18px; border-bottom: 1px solid var(--line); }
.stab {
  font-size: 13px; color: var(--dim); text-decoration: none; padding: 8px 12px;
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.stab:hover { color: var(--ink); }
.stab.on { color: var(--ink); border-bottom-color: var(--warp); }
</style>
