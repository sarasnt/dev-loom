<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import { computed, onMounted } from 'vue'
import { useDashboardStore } from './stores/dashboard'
import AppRail from './components/AppRail.vue'

const store = useDashboardStore()
const { today, buildBadge, models, activeModel } = storeToRefs(store)
const route = useRoute()

// Load the rail's real data (workspace/model/sync/badge) once, on any entry page.
onMounted(() => store.ensureLoaded())

// Onboarding is a full-bleed screen with no rail.
const chromeless = computed(() => route.name === 'onboarding')

// Neutral placeholders shown only for the brief moment before the first load resolves —
// no fixtures, no invented workspace or model.
const railDefaults = {
  workspace: 'My workspace',
  sync: { sources: [] as { key: string; label: string; state: 'healthy' }[], updated: '…' },
  model: { name: 'local model', local: true },
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
      :build-badge="buildBadge"
      :models="models"
      :active-model="activeModel"
      @select-model="store.setModel"
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
