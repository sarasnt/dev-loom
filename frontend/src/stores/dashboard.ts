import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TodayData } from '../types'
import { fetchToday } from '../api/stub'

export const useDashboardStore = defineStore('dashboard', () => {
  const today = ref<TodayData | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load() {
    loading.value = true
    error.value = null
    try {
      today.value = await fetchToday()
    } catch (e) {
      error.value = 'Could not load your dashboard. Retry, or check your connections.'
    } finally {
      loading.value = false
    }
  }

  return { today, loading, error, load }
})
