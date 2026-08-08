<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { useDashboardStore } from './stores/dashboard'
import AppRail from './components/AppRail.vue'

const store = useDashboardStore()
const { today } = storeToRefs(store)
const route = useRoute()

// Onboarding is a full-bleed screen with no rail.
const chromeless = computed(() => route.name === 'onboarding')

const railDefaults = {
  workspace: "sara's workspace",
  sync: {
    sources: [
      { key: 'gh', label: 'GitHub', state: 'healthy' as const },
      { key: 'jira', label: 'Jira', state: 'healthy' as const },
      { key: 'gcal', label: 'Google', state: 'syncing' as const },
      { key: 'mscal', label: 'Microsoft', state: 'healthy' as const },
    ],
    updated: 'syncing…',
  },
  model: { name: 'Qwen3-Coder', local: true },
  boundary: { mode: 'local' as const, label: 'On your machine' },
}
</script>

<template>
  <div v-if="chromeless" class="bleed"><RouterView /></div>
  <div v-else class="app">
    <AppRail
      :workspace="today?.workspace ?? railDefaults.workspace"
      :sync="today?.sync ?? railDefaults.sync"
      :model="today?.model ?? railDefaults.model"
      :boundary="today?.boundary ?? railDefaults.boundary"
      :build-badge="2"
    />
    <RouterView />
  </div>
</template>

<style scoped>
.app { display: grid; grid-template-columns: 212px 1fr; height: 100%; }
.bleed { height: 100%; overflow: auto; }
@media (max-width: 768px) {
  .app { grid-template-columns: 1fr; }
}
</style>
