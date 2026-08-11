import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TodayData, ProvidersData } from '../types'
import { fetchToday, fetchWork, fetchProviders, snoozeToday } from '../api'

// Local models + remote models from any keyed provider (the router maps names → adapter).
function unionModels(p: ProvidersData): string[] {
  const all = [...(p.local.models ?? [])]
  all.push(...(p.agentModels ?? [])) // e.g. claude-code (subscription via host agent)
  if (p.anthropic?.hasKey) all.push(...(p.anthropic.models ?? []))
  if (p.openai?.hasKey) all.push(...(p.openai.models ?? []))
  return all
}

export const useDashboardStore = defineStore('dashboard', () => {
  const today = ref<TodayData | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const buildBadge = ref(0) // count of real failing CI builds, for the rail badge
  const models = ref<string[]>([]) // every model a user can pick (local + agent + keyed remote)
  const agentModels = ref<string[]>([]) // claude-code / claude-cli — Brainstorm-only (exclusive)
  const defaultModel = ref<string>('') // backend's active/default model — the per-screen fallback
  const activeModel = ref<string>('') // model of the CURRENT screen (rail boundary reflects this)

  // Per-screen model choice, bounded to each screen and persisted locally. Handoffs and
  // Brainstorm can each run a different model; there is no single global selection.
  const SCREEN_KEY = 'devloom.screenModels'
  function loadScreenModels(): Record<string, string> {
    try { return JSON.parse(localStorage.getItem(SCREEN_KEY) || '{}') } catch { return {} }
  }
  const screenModels = ref<Record<string, string>>(loadScreenModels())

  // The model for a screen: its saved choice (if still available) else the backend default.
  function modelFor(screen: string): string {
    const m = screenModels.value[screen]
    return m && models.value.includes(m) ? m : defaultModel.value
  }
  // Set a screen's model (local-only; screens pass the model per request). Also reflects it on
  // the rail so the boundary token matches the screen you're on.
  function setModelFor(screen: string, name: string) {
    screenModels.value = { ...screenModels.value, [screen]: name }
    try { localStorage.setItem(SCREEN_KEY, JSON.stringify(screenModels.value)) } catch { /* ignore */ }
    activeModel.value = name
  }
  // Reflect a model on the rail (boundary token) without changing a screen's saved choice —
  // e.g. when a Brainstorm session is locked to claude-cli.
  function reflectModel(name: string) {
    if (name) activeModel.value = name
  }

  // Snooze a Today card and refresh the list from the server's new state.
  async function snoozeItem(id: string) {
    try { today.value = await snoozeToday(id) } catch { /* leave list as-is */ }
  }

  // Brainstorm boundary-crossing preference (persisted; also editable in Settings > General).
  // 'ask' → prompt; 'new' → silently open a new session; 'cancel' → silently ignore the switch.
  type SwitchPref = 'ask' | 'new' | 'cancel'
  const SWITCH_KEY = 'devloom.brainstormSwitch'
  function loadSwitch(): { toCli: SwitchPref; fromCli: SwitchPref } {
    try {
      const s = JSON.parse(localStorage.getItem(SWITCH_KEY) || '{}')
      return { toCli: s.toCli || 'ask', fromCli: s.fromCli || 'ask' }
    } catch { return { toCli: 'ask', fromCli: 'ask' } }
  }
  const brainstormSwitch = ref(loadSwitch())
  function setBrainstormSwitch(dir: 'toCli' | 'fromCli', v: SwitchPref) {
    brainstormSwitch.value = { ...brainstormSwitch.value, [dir]: v }
    try { localStorage.setItem(SWITCH_KEY, JSON.stringify(brainstormSwitch.value)) } catch { /* ignore */ }
  }

  // A seed prompt handed to a freshly-created Brainstorm session (e.g. "Run" on an Agent
  // handoff). The Brainstorm screen consumes it when it opens the matching session: for a chat
  // session it prefills the composer; for a claude-cli session it's typed into the terminal.
  const pendingSeed = ref<{ sessionId: string; text: string; mode: 'chat' | 'cli' } | null>(null)
  function setPendingSeed(sessionId: string, text: string, mode: 'chat' | 'cli') {
    pendingSeed.value = { sessionId, text, mode }
  }
  function takePendingSeed(sessionId: string): { text: string; mode: 'chat' | 'cli' } | null {
    const s = pendingSeed.value
    if (!s || s.sessionId !== sessionId) return null
    pendingSeed.value = null
    return { text: s.text, mode: s.mode }
  }

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
      agentModels.value = p.agentModels ?? []
      defaultModel.value = p.local.active ?? p.local.defaultModel ?? (models.value[0] ?? '')
      if (!activeModel.value) activeModel.value = defaultModel.value
    } catch {
      // leave the previous value
    }
  }

  return {
    today, loading, error, buildBadge, models, agentModels, defaultModel, activeModel,
    screenModels, modelFor, setModelFor, reflectModel, snoozeItem,
    brainstormSwitch, setBrainstormSwitch, load, ensureLoaded,
    pendingSeed, setPendingSeed, takePendingSeed,
  }
})
