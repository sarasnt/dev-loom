// Domain types for the Today screen — mirror the unified model in SPEC.md §15.

export type WorkItemType = 'pr' | 'build' | 'stale' | 'task' | 'review' | 'calendar' | 'doc'

export type ChipTone = 'neutral' | 'warn' | 'fail' | 'stale'

export interface SignalChip {
  label: string // mono value, e.g. "wait:51h"
  tone?: ChipTone
}

export interface EvidenceRef {
  id: string // e.g. "PR #482", "TICKET-91"
  title?: string
  boundary: 'local' | 'remote'
}

export interface SignalComponent {
  name: string
  value: string // mono, e.g. "51h"
  normalized: number // 0..1 → bar width
  weight: number
}

export interface Recommendation {
  id: string
  rank: number
  type: WorkItemType
  title: string
  source: string // e.g. "GitHub", "Jira"
  why: string // grotesque reasoning
  isHypothesis: boolean
  chips: SignalChip[]
  lead?: boolean
  signals?: SignalComponent[]
  evidence?: EvidenceRef[]
  score?: number
  actions: string[]
  url?: string | null // open the item in its source (GitHub/Jira/Notion)
  handled: boolean // you dealt with it (persists; drops from the Briefing)
  planned: boolean // in today's plan
}

// Today "Briefing" mode (spec §5): since-yesterday diff + the urgent set + today's plan.
export interface Briefing {
  newItems: Recommendation[]
  resolved: Recommendation[]
  waiting: Recommendation[]
  needsYou: Recommendation[]
  plan: Recommendation[]
}

export interface SyncSource {
  key: string
  label: string
  state: 'healthy' | 'syncing' | 'partial' | 'error'
}

export interface Boundary {
  mode: 'local' | 'remote' | 'mixed'
  label: string
}

export interface TodayData {
  workspace: string
  user: string
  now: string
  changed: { text: string; since: string } | null
  sync: { sources: SyncSource[]; updated: string }
  model: { name: string; local: boolean }
  boundary: Boundary
  next: Recommendation[]
  everythingCount: number
  snoozedCount: number
  briefing: Briefing
}

// ---- Work browser ----
export interface WorkRow {
  id: string
  type: WorkItemType
  glyph: string
  title: string
  status: string
  statusTone: 'warn' | 'fail' | 'stale' | 'healthy' | 'info'
  meta: string[]
  source: string
  category?: string
  description?: string | null
  parentId?: string | null
}

// ---- Build-failure analysis (SPEC §23) ----
export type Confidence = 'high' | 'med' | 'low' | 'n/a'
export interface LogLine {
  text: string
  kind?: 'normal' | 'fail' | 'omitted' | 'redacted'
}
export interface Hypothesis {
  rank: number
  text: string
  confidence: Confidence
  evidence: EvidenceRef[]
  uncited?: boolean
}
export interface BuildFailure {
  id: string
  repo: string
  branch: string
  pr?: string | null
  run: string
  failedAgo: string
  boundary: Boundary
  summary: string
  summaryConfidence: Confidence
  failingJob: string
  failingStep: string
  failingTest: string
  redacted: boolean
  log: LogLine[]
  causes: Hypothesis[]
  related: EvidenceRef[]
  diagnostics: string[]
  fixes: string[]
  analyzedBy?: string
}

// ---- Coding-agent handoff (SPEC §24) ----
export interface Handoff {
  id: string
  title: string
  target: string
  version: number
  boundary: Boundary
  rendered: string // the mono artifact body
  safety: { allow: string[]; forbid: string[] }
  sources: EvidenceRef[]
  repo: string // owner/name slug of the target repo (for resolving a local repo to run in)
  branch: string
}

// ---- Sources (multi-source configuration) ----
export interface SourceField {
  key: string
  label: string
  type: string // text | url | password | number
  secret: boolean
  required: boolean
  placeholder?: string
}
export interface SourceDeployment {
  id: string
  label: string
  fields: SourceField[]
}
export interface SourceType {
  type: string
  label: string
  deployments: SourceDeployment[]
}
export interface SourceView {
  id: string
  type: string
  typeLabel: string
  deployment: string
  name: string
  baseUrl?: string | null
  enabled: boolean
  state: 'connected' | 'not_connected'
  detail?: string | null
  items: number
  note?: string | null
  actions: string[]
}

// ---- Local repositories (via host agent) ----
export interface RepoView {
  id: string
  path: string
  name: string
  host: string // github | bitbucket | gitlab | git | none
  slug: string
  branch: string
  remote: string
  dirty: boolean
  ahead: number
  behind: number
  userName: string
  userEmail: string
  live: boolean
  localOnly: boolean // brainstorm this repo only with local models (never remote)
  staged: number
  unstaged: number
  untracked: number
  hasUpstream: boolean
  upstream: string | null // e.g. "origin/feature/login", or null if no tracking
  operation: string | null // 'merge' | 'rebase' | 'cherry-pick' | 'revert' | null
  commonDir: string // shared git dir — worktrees of one repo share this
  isLinkedWorktree: boolean // true = a linked worktree (not the main checkout)
}

// A git worktree of a repo (repos spec §9).
export interface WorktreeInfo {
  path: string
  branch: string | null
  head: string | null
  bare: boolean
  detached: boolean
  locked: boolean
  tracked: boolean // DevLoom already tracks this worktree dir as its own repo
  repoId: string | null
}

// Where the current branch forks from + how far HEAD has drifted (repos spec §6/§7.3).
export interface SourceStatus {
  source: string | null // resolved source branch, or null when unknown
  defaultBranch: string | null // repo default (origin/HEAD)
  origin: 'override' | 'pr' | 'default' | 'unknown' // how `source` was resolved
  hasSource: boolean // the source ref actually exists
  missing: boolean // an override/source was named but the ref is gone
  sourceAhead: number // commits on HEAD not in source
  sourceBehind: number // commits on source not in HEAD (HEAD is outdated by N)
}

// Read-only conflict prediction for merging the source branch into HEAD (repos spec §7.4).
export interface ConflictStatus {
  state: 'clean' | 'conflict' | 'unknown' | 'unable' | 'stale'
  files: string[] // conflicting paths (when state === 'conflict')
  ref: string | null // the source ref compared against
  reason: string | null // explanation for unknown/unable
  lastFetch: string | null // ISO instant of the last successful fetch (null = never)
  stale: boolean // refs older than the freshness window → prompt a refresh
}

// ---- Repo history + squash (repos spec §8–§9) ----
export interface CommitRow {
  hash: string
  short: string
  author: string
  email: string
  date: string // ISO
  merge: boolean
  subject: string
  published: boolean // already on the upstream (squash needs elevated confirm)
}
export interface HistoryResult {
  commits: CommitRow[]
  sourceRef: string | null // set when the unique-to-source filter was applied
  hasUpstream: boolean
  error?: string
}
export interface CommitDetail {
  hash: string
  short: string
  author: string
  email: string
  date: string
  committer: string
  commitDate: string
  parents: string[]
  message: string
  files: { status: string; file: string }[]
}
export interface SquashResult {
  ok: boolean
  needsConfirm?: boolean
  error?: string
  backupRef?: string
  newHead?: string
  recover?: string
}

export interface BrowseDir { name: string; path: string; repo: boolean }
export interface BrowseResult {
  path: string
  parent: string | null
  isRepo: boolean
  drives: { name: string; path: string }[]
  dirs: BrowseDir[]
}
export interface RepoChange { file: string; status: string }
export interface RepoChanges {
  staged: RepoChange[]
  unstaged: RepoChange[]
  untracked: RepoChange[]
}

// ---- Integrations ----
export interface Integration {
  key: string
  name: string
  state: 'connected' | 'partial' | 'not_connected'
  detail?: string
  scopes?: string[]
  note?: string
  actions: string[]
}

// ---- Model providers ----
export interface LocalProvider {
  name: string
  defaultModel: string
  active?: string
  models: string[]
  loaded: boolean
}
export interface KeyProvider {
  name: string
  key: string // provider id: 'anthropic' | 'openai'
  boundaryLabel: string
  hasKey: boolean
  valid?: boolean
  capCents?: number
  usedCents?: number
  note?: string
  models?: string[]
  maskedKey?: string | null
}
export interface ProvidersData {
  local: LocalProvider
  anthropic: KeyProvider
  openai: KeyProvider
  fallbackOn: boolean
  canStoreKeys: boolean
  agentModels?: string[] // e.g. ["claude-code"] when the host agent + CLI are available
}

// ---- Privacy & data boundary ----
export interface EgressEntry {
  time: string
  action: string
  to: string
  tokens: string
}
export interface PrivacyData {
  defaultBoundary: string
  localOnlyRepos: string[]
  egress: EgressEntry[]
}

// ---- Model monitoring (LangChain4j ChatModelListener) ----
export interface ModelCall {
  at: number
  provider: string
  model: string
  latencyMs: number
  inputTokens: number
  outputTokens: number
  ok: boolean
  error: string | null
}
export interface ModelAgg {
  provider: string
  model: string
  calls: number
  errors: number
  avgLatencyMs: number
  inputTokens: number
  outputTokens: number
}
export interface MonitoringData {
  models: ModelAgg[]
  recent: ModelCall[]
  totals: { calls: number; errors: number; inputTokens: number; outputTokens: number }
  langfuseEnabled: boolean
  metricsPath: string
}

// ---- user settings ----
export interface NotifySettings {
  enabled: boolean
  digestTime: string // HH:mm local
  quietStart: string // HH:mm
  quietEnd: string // HH:mm
  urgentCi: boolean
  urgentReview: boolean
  prWaitHours: number
}
export interface SettingsData {
  terminalWorkdir: string // default working dir for claude-cli terminals (empty = home)
  repoDirs: string[] // parent directories scanned by "Sync" on the Repos page
  notify: NotifySettings // desktop-notification preferences
  fleetWorktreesDefault: boolean // default: isolate edit runs in a git worktree
  gitPushProtection: 'off' | 'all' | 'protected' // global push guardrail
  gitProtectedPatterns: string // comma/newline list of protected-branch regexes
}

// Effective push protection for one repo (its override, or the global default).
export interface PushProtection {
  mode: 'off' | 'all' | 'protected'
  patterns: string
  overridden: boolean
  globalMode: 'off' | 'all' | 'protected'
}

// ---- local model management (Ollama) ----
export interface InstalledModel {
  name: string
  size: number // bytes
}

// ---- claude-cli embedded terminal ----
export interface TerminalInfo {
  cwd: string | null // repo dir, or null → agent opens in home
  sessionId: string // Claude Code session id (shared with claude-code chat + `claude --resume`)
  resume: boolean // true → `claude --resume <id>`; false → `claude --session-id <id>`
}

// ---- Brainstorm ----
export interface BrainstormMessage {
  role: 'you' | 'ai'
  text: string
  model?: string
  hypothesis?: boolean
  sources?: EvidenceRef[]
}
export interface ContextItem {
  id: string
  kind: string // repo | workitem | file | note
  ref?: string | null
  label: string
  pinned: boolean
}
export interface BrainstormSession {
  id: string
  title: string
  visibility: 'personal' | 'workspace'
  model: string
  boundary: Boundary
  inContext: ContextItem[]
  messages: BrainstormMessage[]
  repoPath?: string | null
  cliMode?: boolean // session lives in the claude-cli terminal (locked to terminal mode)
  claudeSessionId?: string | null // Claude session id (for the `claude --resume` hint)
  localOnly?: boolean // session's repo is local-only → only local models allowed
}
export interface BrainstormData {
  sessions: { id: string; title: string; cliMode?: boolean }[]
  active: BrainstormSession
}

// ---- Fleet (agent runs) ----
export type RunStatus = 'running' | 'review' | 'done' | 'failed' | 'canceled' | 'active' | 'ended' | 'input'
export interface AgentRun {
  id: string
  title: string
  repoPath: string
  runDir: string | null
  branch: string | null
  kind: 'interactive' | 'background' | 'chat'
  permission: 'readonly' | 'edit' | null
  allowTests: boolean
  isolated: boolean
  model: string | null
  status: RunStatus
  resultSummary: string | null
  error: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  claudeSessionId: string | null
  brainstormSessionId: string | null
}
export interface RunLaunch {
  repoId: string
  prompt: string
  model?: string
  permission: 'readonly' | 'edit'
  allowTests: boolean
  isolate: boolean
}

// ---- Model capabilities (skills / MCP servers / plugins) ----
export interface McpServer {
  name: string
  transport: string // 'stdio' | 'http' | 'sse'
  command: string // command (stdio) or URL (http/sse)
  args: string[]
  env: string[] // key names only — values are never sent back to the UI
}
export interface SkillInfo {
  dir: string
  name: string
  description: string
  path: string
  managed: 'git' | 'local'
}
export interface PluginInfo {
  id: string
  name: string
  marketplace: string
  version: string
  enabled: boolean
  missing?: boolean
}
export interface Capabilities {
  mcp: McpServer[]
  skills: SkillInfo[]
  plugins: PluginInfo[]
  skillsDir?: string
  agentUp?: boolean
  error?: string
}
export interface SkillDetail {
  ok: boolean
  dir: string
  name: string
  description: string
  body: string
  error?: string
}

// ---- Onboarding ----
export interface OnboardStep {
  n: number | string
  title: string
  detail: string
  state: 'done' | 'now' | 'todo'
  action?: string
}
