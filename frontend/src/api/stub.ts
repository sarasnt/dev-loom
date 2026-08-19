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
  snoozed: [],
  next: [],
  briefing: { newItems: [], resolved: [], needsYou: [], plan: [] },
}

// Generic latency wrapper so every screen exercises its loading state.
function delay<T>(value: T, ms = 350): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

export function fetchToday(): Promise<TodayData> {
  return delay(TODAY, 300)
}
export function snoozeToday(_id: string): Promise<TodayData> {
  return delay(TODAY, 150)
}
export function unsnoozeToday(_id: string): Promise<TodayData> {
  return delay(TODAY, 150)
}
export function toggleHandled(_id: string): Promise<TodayData> {
  return delay(TODAY, 150)
}
export function togglePlan(_id: string): Promise<TodayData> {
  return delay(TODAY, 150)
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
export function fetchBuildFailure(id: string, _model?: string): Promise<BuildFailure> {
  return delay({ ...EMPTY_BUILD, id: id || 'none' })
}
export function fetchLatestBuild(_model?: string): Promise<BuildFailure> {
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
  repo: '—',
  branch: '—',
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
  anthropic: { name: 'Anthropic', key: 'anthropic', boundaryLabel: 'leaves for Anthropic', hasKey: false, models: [] },
  openai: { name: 'OpenAI', key: 'openai', boundaryLabel: 'leaves for OpenAI', hasKey: false, models: [] },
  fallbackOn: false,
  canStoreKeys: false,
}
export function fetchProviders(): Promise<ProvidersData> {
  return delay(PROVIDERS)
}
export function openBrainstormTerminal(_id: string): Promise<import('../types').TerminalInfo> {
  return delay({ cwd: null, sessionId: 'offline-stub', resume: false })
}
export function fetchInstalledModels(): Promise<import('../types').InstalledModel[]> {
  return delay([] as import('../types').InstalledModel[])
}
export function removeModel(_name: string): Promise<{ removed: boolean }> {
  return delay({ removed: false })
}
type ModelAdvancedMap = { models: Record<string, import('../types').ModelAdvanced> }
export function fetchModelAdvanced(): Promise<ModelAdvancedMap> {
  return delay({ models: {} })
}
export function saveModelAdvanced(
  model: string,
  a: import('../types').ModelAdvanced,
): Promise<ModelAdvancedMap> {
  return delay({ models: { [model]: a } })
}
const NOTIFY_STUB = {
  enabled: false, digestTime: '08:30', quietStart: '22:00', quietEnd: '08:00',
  urgentCi: true, urgentReview: true, prWaitHours: 24,
}
const ADVANCED_STUB: import('../types').AdvancedSettings = {
  groundedTemperature: '', groundedTopP: '', creativeTemperature: '', maxSteps: '', seed: '', numCtx: '', judgeEnabled: true,
  defaults: { groundedTemperature: 0.1, groundedTopP: 0.9, creativeTemperature: 0.7, maxSteps: 6, numCtx: 8192 },
}
export function fetchSettings(): Promise<import('../types').SettingsData> {
  return delay({ terminalWorkdir: '', repoDirs: [], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function saveTerminalWorkdir(path: string): Promise<import('../types').SettingsData> {
  return delay({ terminalWorkdir: path, repoDirs: [], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function addRepoDir(path: string): Promise<import('../types').SettingsData> {
  return delay({ terminalWorkdir: '', repoDirs: [path], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function removeRepoDir(): Promise<import('../types').SettingsData> {
  return delay({ terminalWorkdir: '', repoDirs: [], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function saveNotificationSettings(
  b: Partial<import('../types').NotifySettings>,
): Promise<import('../types').SettingsData> {
  return delay({ terminalWorkdir: '', repoDirs: [], notify: { ...NOTIFY_STUB, ...b }, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function testNotification(): Promise<{ ok: boolean; error?: string }> {
  return delay({ ok: false, error: 'offline' })
}
export function syncRepos() {
  return delay({ added: 0, dirs: [] as string[], agentUp: false, repos: [] as import('../types').RepoView[] })
}
export function fetchMonitoring(windowDays = 7): Promise<import('../types').MonitoringData> {
  return delay({
    models: [],
    recent: [],
    totals: { calls: 0, errors: 0, inputTokens: 0, outputTokens: 0 },
    langfuseEnabled: false,
    metricsPath: '/actuator/metrics/devloom.llm.calls',
    windowDays,
    retentionDays: 30,
  })
}
export function fetchHandoffs(): Promise<import('../types').HandoffSummary[]> {
  return delay([])
}
export function generateHandoff(_buildId?: string): Promise<import('../types').Handoff> {
  return delay(HANDOFF)
}
export function deleteHandoff(id: string): Promise<{ deleted: string }> {
  return delay({ deleted: id })
}
export function setMonitoringRetention(days: number): Promise<{ retentionDays: number }> {
  return delay({ retentionDays: days })
}
export function setActiveModel(_name: string): Promise<ProvidersData> {
  return delay(PROVIDERS)
}
export function setProviderKey(_provider: string, _key: string): Promise<ProvidersData> {
  return delay(PROVIDERS)
}
export function clearProviderKey(_provider: string): Promise<ProvidersData> {
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
export function fetchBrainstormSession(_id: string) {
  return delay(BRAINSTORM.active)
}
export function createBrainstormSession(title?: string, _repoPath?: string, model?: string) {
  return delay({
    ...BRAINSTORM.active, id: 'new', title: title || 'New brainstorm', messages: [],
    cliMode: model === 'claude-cli',
  })
}
export function deleteBrainstormSession(id: string) {
  return delay({ deleted: id })
}
export function renameBrainstormSession() {
  return delay(BRAINSTORM.active)
}
export function fetchRepoSessions() {
  return delay([] as { id: string; title: string; repoPath: string }[])
}
export function addBrainstormContext() {
  return delay(BRAINSTORM.active)
}
export function removeBrainstormContext() {
  return delay(BRAINSTORM.active)
}

export function sendBrainstorm(_sessionId: string, message: string, sourceIds: string[], _model?: string) {
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

// ---- multi-source configuration (offline: empty) ----
export function fetchSourceTypes() {
  return delay([] as import('../types').SourceType[])
}
export function fetchSources() {
  return delay([] as import('../types').SourceView[])
}
export function createSource() {
  return delay(null as unknown as import('../types').SourceView)
}
export function testSourceConfig() {
  return delay({ ok: false, error: 'offline stub' })
}
export function updateSource() {
  return delay(null as unknown as import('../types').SourceView)
}
export function deleteSourceInstance(id: string) {
  return delay({ deleted: id })
}
export function syncSourceInstance(id: string) {
  return delay({ id, ingested: 0 })
}

// ---- local repositories (offline: agent down) ----
export function fetchRepos() {
  return delay({ agentUp: false, repos: [] as import('../types').RepoView[] })
}
export function scanRepoFolder() {
  return delay([] as import('../types').RepoView[])
}
export function addRepoPath() {
  return delay([] as import('../types').RepoView[])
}
export function removeRepo(id: string) {
  return delay({ removed: id })
}
export function setRepoIdentity() {
  return delay(null as unknown as import('../types').RepoView)
}
export function setRepoLocalOnly(_id: string, value: boolean) {
  return delay({ localOnly: value } as unknown as import('../types').RepoView)
}
export function repoPull() {
  return delay({ ok: false, output: 'offline' })
}
export function repoPush() {
  return delay({ ok: false, output: 'offline', rejected: false, forced: false })
}
export function repoAbort() {
  return delay({ ok: false, operation: null as string | null, output: 'offline' })
}
export function repoPr() {
  return delay({ ok: false, error: 'offline' })
}
export function browseFs() {
  return delay({ path: '', parent: null, isRepo: false, drives: [], dirs: [] } as import('../types').BrowseResult)
}
export function repoChanges() {
  return delay({ staged: [], unstaged: [], untracked: [] } as import('../types').RepoChanges)
}
export function repoStage() {
  return delay({ ok: false, output: 'offline' })
}
export function repoUnstage() {
  return delay({ ok: false, output: 'offline' })
}
export function repoCommit() {
  return delay({ ok: false, output: 'offline' })
}
export function repoBranches() {
  return delay({ current: '', local: [] as string[] })
}
export function repoCheckout(_id: string, branch: string) {
  return delay({ ok: false, branch, output: 'offline' })
}
export function repoSource() {
  return delay({
    source: 'main', defaultBranch: 'main', origin: 'default' as const,
    hasSource: true, missing: false, sourceAhead: 3, sourceBehind: 0,
  })
}
export function repoWorktrees() {
  return delay([] as import('../types').WorktreeInfo[])
}
export function setRepoSource(_id: string, _branch: string, source: string | null) {
  return delay({
    source, defaultBranch: 'main', origin: (source ? 'override' : 'default') as 'override' | 'default',
    hasSource: !!source, missing: false, sourceAhead: 3, sourceBehind: 0,
  })
}
export function repoFetch() {
  return delay({ ok: false, error: 'offline', at: null as string | null })
}
export function repoConflict() {
  return delay({
    state: 'clean' as const, files: [] as string[], ref: 'origin/main',
    reason: null as string | null, lastFetch: null as string | null, stale: true,
  })
}
export function fleetRuns() { return delay([] as import('../types').AgentRun[]) }
export function fleetRun() { return delay(null as unknown as import('../types').AgentRun) }
export function launchRun() { return delay(null as unknown as import('../types').AgentRun) }
export function cancelRun() { return delay(null as unknown as import('../types').AgentRun) }
export function fleetRunChanges() { return delay({ staged: [], unstaged: [], untracked: [] } as import('../types').RepoChanges) }
export function rerunRun() { return delay(null as unknown as import('../types').AgentRun) }
export function deleteRun() { return delay({ deleted: '' }) }
export function applyRun() { return delay(null as unknown as import('../types').AgentRun) }
export function discardRun() { return delay(null as unknown as import('../types').AgentRun) }
export function saveFleetSettings() {
  return delay({ terminalWorkdir: '', repoDirs: [], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function saveGitSettings() {
  return delay({ terminalWorkdir: '', repoDirs: [], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: ADVANCED_STUB })
}
export function repoPushProtection() {
  return delay({ mode: 'protected' as const, patterns: 'main, master, develop, dev', overridden: false, globalMode: 'protected' as const })
}
export function setRepoPushProtection() {
  return delay({ mode: 'protected' as const, patterns: 'main, master, develop, dev', overridden: true, globalMode: 'protected' as const })
}
export function repoHistory(): Promise<import('../types').HistoryResult> {
  return delay({ commits: [], sourceRef: null, hasUpstream: false })
}
export function repoCommitDetail(): Promise<import('../types').CommitDetail> {
  return delay(null as unknown as import('../types').CommitDetail)
}
export function repoSquash(): Promise<import('../types').SquashResult> {
  return delay({ ok: false, error: 'offline' })
}
export function fetchCapabilities(): Promise<import('../types').Capabilities> {
  return delay({ mcp: [], skills: [], plugins: [], skillsForModels: [], mcpForModels: [], agentUp: false, error: 'offline' })
}
export function addMcpServer() { return delay({ ok: false, error: 'offline' }) }
export function removeMcpServer() { return delay({ ok: false, error: 'offline' }) }
export function togglePlugin() { return delay({ ok: false }) }
export function saveSkill() { return delay({ ok: false, error: 'offline' }) }
export function fetchSkill(): Promise<import('../types').SkillDetail> {
  return delay({ ok: false, dir: '', name: '', description: '', body: '', error: 'offline' })
}
export function removeSkill() { return delay({ ok: false, error: 'offline' }) }
export function installSkillRepo() {
  return delay({ ok: false, dir: undefined as string | undefined, error: 'offline' })
}
export function fetchBackup(): Promise<import('../types').BackupStatus> {
  return delay({ dir: '', remote: '', everyHours: 0, includeSkills: true, push: true, lastAt: null, lastResult: null })
}
export function previewBackup() { return delay({} as Record<string, unknown>) }
export function configureBackup(): Promise<import('../types').BackupStatus> {
  return delay({ dir: '', remote: '', everyHours: 0, includeSkills: true, push: true, lastAt: null, lastResult: null })
}
export function runBackup() { return delay({ ok: false, summary: undefined as string | undefined, error: 'offline' }) }
export function restoreBackup() {
  return delay({ ok: false, settings: 0, repos: 0, sources: 0, skills: 0, note: undefined as string | undefined, error: 'offline' })
}
export function setSkillsForModels(): Promise<import('../types').Capabilities> {
  return delay({ mcp: [], skills: [], plugins: [], skillsForModels: [], mcpForModels: [], agentUp: false, error: 'offline' })
}
export function setMcpForModels(): Promise<import('../types').Capabilities> {
  return delay({ mcp: [], skills: [], plugins: [], skillsForModels: [], mcpForModels: [], agentUp: false, error: 'offline' })
}
export function saveAdvancedSettings(
  b: Partial<Omit<import('../types').AdvancedSettings, 'defaults'>>,
): Promise<import('../types').SettingsData> {
  return delay({ terminalWorkdir: '', repoDirs: [], notify: NOTIFY_STUB, fleetWorktreesDefault: true, gitPushProtection: 'protected' as const, gitProtectedPatterns: 'main, master, develop, dev', advanced: { ...ADVANCED_STUB, ...b } })
}
