import type {
  TodayData,
  WorkRow,
  BuildFailure,
  Handoff,
  Integration,
  ProvidersData,
  PrivacyData,
  BrainstormData,
  OnboardStep,
} from '../types'

// Offline stub — only used when VITE_USE_STUB=true (frontend-only work with no backend).
// The real app always talks to the backend (http.ts), which serves live data from your
// connected sources. This stub is intentionally generic: neutral placeholders, no fixtures
// masquerading as real work.

const TODAY: TodayData = {
  workspace: 'My workspace',
  user: 'you',
  now: '—',
  changed: {
    text: 'Offline preview — start the backend to see your real work.',
    since: '',
  },
  sync: {
    updated: 'no sources connected',
    sources: [],
  },
  model: { name: 'local model', local: true },
  boundary: { mode: 'local', label: 'On your machine' },
  everythingCount: 0,
  snoozedCount: 0,
  next: [],
}

// Generic latency wrapper so every screen exercises its loading state.
function delay<T>(value: T, ms = 350): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

export function fetchToday(): Promise<TodayData> {
  return delay(TODAY, 300)
}

// ---- Work browser ----
export function fetchWork(): Promise<WorkRow[]> {
  return delay([])
}

// ---- Build-failure analysis ----
const EMPTY_BUILD: BuildFailure = {
  id: 'none',
  repo: '—',
  branch: '—',
  pr: null,
  run: '—',
  failedAgo: '—',
  boundary: { mode: 'local', label: 'On your machine' },
  summary:
    'Offline preview. When the backend is running and a connected repo has a failed GitHub Actions run, its redacted log and local-model analysis appear here.',
  summaryConfidence: 'n/a',
  failingJob: '—',
  failingStep: '—',
  failingTest: '—',
  redacted: false,
  log: [{ text: '(no build failures)', kind: 'omitted' }],
  causes: [],
  related: [],
  diagnostics: ['Start the backend and connect a GitHub repo with CI.'],
  fixes: [],
  analyzedBy: 'deterministic',
}
export function fetchBuildFailure(id: string): Promise<BuildFailure> {
  return delay({ ...EMPTY_BUILD, id: id || 'none' })
}
export function fetchLatestBuild(): Promise<BuildFailure> {
  return delay(EMPTY_BUILD)
}

// ---- Handoff ----
const HANDOFF: Handoff = {
  id: 'none',
  title: 'No build selected',
  target: 'Claude Code',
  version: 1,
  boundary: { mode: 'local', label: 'On your machine' },
  rendered:
    '# Agent Handoff\n\nOffline preview. Open a real failed build to generate a handoff from its redacted evidence.',
  safety: {
    allow: ['Verify the fix with tests before claiming done'],
    forbid: ['Do NOT push / merge / deploy / delete without approval'],
  },
  sources: [],
}
export function fetchHandoff(_id: string): Promise<Handoff> {
  return delay(HANDOFF)
}

// ---- Integrations ----
const INTEGRATIONS: Integration[] = [
  { key: 'github', name: 'GitHub', state: 'not_connected', actions: ['Connect'] },
  { key: 'jira', name: 'Jira', state: 'not_connected', actions: ['Connect'] },
  { key: 'gcal', name: 'Google Calendar', state: 'not_connected', actions: ['Connect'] },
  { key: 'notion', name: 'Notion', state: 'not_connected', actions: ['Connect'] },
  { key: 'mscal', name: 'Microsoft Calendar', state: 'not_connected', actions: ['Connect'] },
]
export function fetchIntegrations(): Promise<Integration[]> {
  return delay(INTEGRATIONS)
}

// ---- Providers ----
const PROVIDERS: ProvidersData = {
  local: { name: 'Ollama', defaultModel: 'local model', active: 'local model', loaded: false, models: [] },
  anthropic: { name: 'Anthropic', boundaryLabel: 'leaves for Anthropic', hasKey: false },
  openai: { name: 'OpenAI', boundaryLabel: 'leaves for OpenAI', hasKey: false, note: 'Uses the Responses API · stateless' },
  fallbackOn: false,
}
export function fetchProviders(): Promise<ProvidersData> {
  return delay(PROVIDERS)
}
export function setActiveModel(_name: string): Promise<ProvidersData> {
  return delay(PROVIDERS)
}

// ---- Privacy ----
const PRIVACY: PrivacyData = {
  defaultBoundary: 'Local-first — nothing leaves unless you add a key and approve it.',
  localOnlyRepos: [],
  egress: [{ time: '—', action: 'Nothing has left your machine', to: '', tokens: '' }],
}
export function fetchPrivacy(): Promise<PrivacyData> {
  return delay(PRIVACY)
}

// ---- Brainstorm ----
const BRAINSTORM: BrainstormData = {
  sessions: [{ id: 'new', title: 'New brainstorm' }],
  active: {
    id: 'new',
    title: 'New brainstorm',
    visibility: 'personal',
    model: 'local model',
    boundary: { mode: 'local', label: 'On your machine' },
    inContext: [],
    messages: [],
  },
}
export function fetchBrainstorm(): Promise<BrainstormData> {
  return delay(BRAINSTORM)
}

export function sendBrainstorm(
  message: string,
  sourceIds: string[],
  _history: { role: string; text: string }[] = [],
) {
  const reply = {
    role: 'ai' as const,
    text: `(offline stub) You asked: "${message}". Attach a backend + local model to get a real reply.`,
    model: 'stub-deterministic',
    hypothesis: true,
    sources: sourceIds.map((id) => ({ id, boundary: 'local' as const })),
  }
  return delay(reply, 500)
}

// ---- Onboarding ----
const ONBOARDING: OnboardStep[] = [
  { n: 1, title: 'Sign in', detail: 'Local single-user for now', state: 'now' },
  { n: 2, title: 'Connect GitHub', detail: 'PRs, issues and CI', state: 'todo', action: 'Connect' },
  { n: 3, title: 'Connect Jira & calendars', detail: 'Jira · Google Calendar', state: 'todo', action: 'Connect' },
  { n: 4, title: 'Choose a model', detail: 'Local & free via Ollama — or add a key later', state: 'todo' },
  { n: 5, title: 'Mark local-only repos', detail: 'These never leave your machine', state: 'todo' },
]
export function fetchOnboarding(): Promise<OnboardStep[]> {
  return delay(ONBOARDING, 150)
}

export function syncSource(source: string) {
  return delay({ source, ingested: 0 }, 400)
}
export function disconnectSource(source: string) {
  return delay({ source, removed: 0 }, 300)
}
