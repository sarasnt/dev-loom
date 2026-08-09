import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TodayData, ProvidersData } from '../types'
import { fetchToday, fetchWork, fetchProviders, setActiveModel } from '../api'

// Local models + remote models from any keyed provider (the router maps names → adapter).
function unionModels(p: ProvidersData): string[] {
  const all = [...(p.local.models ?? [])]
  if (p.anthropic?.hasKey) all.push(...(p.anthropic.models ?? []))
  if (p.openai?.hasKey) all.push(...(p.openai.models ?? []))
  return all
}

export const useDashboardStore = defineStore('dashboard', () => {
  const today = ref<TodayData | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const buildBadge = ref(0) // count of real failing CI builds, for the rail badge
  const models = ref<string[]>([]) // local models available to switch between
  const activeModel = ref<string>('') // currently selected model

  async function load() {
    loading.value = true
    error.value = null
    try {
      today.value = await fetchToday()
      await Promise.all([refreshBadge(), refreshModels()])
    } catch (e) {
      error.value = 'Could not load your dashboard. Retry, or check your connections.'
    } finally {
      loading.value = false
    }
  }

  // Populate the rail (workspace/model/sync/badge) on any page, without a loading state.
  async function ensureLoaded() {
    if (today.value === null && !loading.value) {
      await load()
    } else {
      await Promise.all([refreshBadge(), refreshModels()])
    }
  }

  async function refreshBadge() {
    try {
      const work = await fetchWork()
      buildBadge.value = work.filter((w) => w.type === 'build').length
    } catch {
      // leave the previous value
    }
  }

  async function refreshModels() {
    try {
      const p = await fetchProviders()
      models.value = unionModels(p)
      activeModel.value = p.local.active ?? p.local.defaultModel ?? ''
    } catch {
      // leave the previous value
    }
  }

  // Switch the active model (rail dropdown) — persists on the backend and updates the rail.
  async function setModel(name: string) {
    activeModel.value = name // optimistic
    try {
      const p = await setActiveModel(name)
      // Keep the FULL union so the selected remote model stays in the list (don't shrink to local).
      models.value = unionModels(p)
      activeModel.value = p.local.active ?? name
      if (today.value) today.value.model = { name: activeModel.value, local: false }
    } catch {
      // keep the optimistic value
    }
  }

  return { today, loading, error, buildBadge, models, activeModel, load, ensureLoaded, setModel }
})
