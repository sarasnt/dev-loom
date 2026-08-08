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

// Stubbed API — stands in for GET /api/v1/dashboard + /api/v1/next (SPEC.md §34).
// Swapped for a real fetch() client once the backend exists.

const TODAY: TodayData = {
  workspace: "sara's workspace",
  user: 'sara.santos',
  now: 'Tue 8 Aug · 09:14',
  changed: {
    text: '3 PRs merged · 1 build broke & recovered · 2 reviews now waiting on you',
    since: 'since Mon 17:30',
  },
  sync: {
    updated: 'updated 2m ago',
    sources: [
      { key: 'gh', label: 'GitHub', state: 'healthy' },
      { key: 'jira', label: 'Jira', state: 'healthy' },
      { key: 'gcal', label: 'Google', state: 'syncing' },
      { key: 'mscal', label: 'Microsoft', state: 'healthy' },
    ],
  },
  model: { name: 'Qwen3-Coder', local: true },
  boundary: { mode: 'local', label: 'On your machine' },
  everythingCount: 37,
  snoozedCount: 5,
  next: [
    {
      id: '482',
      rank: 1,
      type: 'pr',
      title: 'Review PR #482',
      source: 'acme/billing',
      why: 'A teammate has been blocked 2 days, CI is green, and it’s tagged for Thursday’s release. Clearing it unblocks them and de-risks the cut.',
      isHypothesis: true,
      lead: true,
      chips: [
        { label: 'blocks:1', tone: 'warn' },
        { label: 'wait:51h' },
        { label: 'release ⚑', tone: 'warn' },
        { label: 'ci:green' },
      ],
      score: 0.86,
      signals: [
        { name: 'blocks teammate', value: '1 dev', normalized: 0.92, weight: 0.9 },
        { name: 'review wait', value: '51h', normalized: 0.7, weight: 0.7 },
        { name: 'release impact', value: 'tagged', normalized: 0.8, weight: 0.8 },
        { name: 'freshness', value: '2m ago', normalized: 1.0, weight: 1.0 },
      ],
      evidence: [
        { id: 'PR #482', title: 'acme/billing', boundary: 'local' },
        { id: 'TICKET-91', title: 'Checkout 500s', boundary: 'local' },
      ],
      actions: ['Open', 'Why', 'Snooze', 'Override'],
    },
    {
      id: '1893',
      rank: 2,
      type: 'build',
      title: 'Fix CI on feature/pricing',
      source: 'acme/billing',
      why: 'First failure on this branch — your last commit d4e5f6 is the only change touching the failing test.',
      isHypothesis: true,
      chips: [
        { label: 'build #1893 ⚑', tone: 'fail' },
        { label: 'age:22m' },
        { label: '1 test' },
      ],
      actions: ['Analyze', 'Why', 'Snooze'],
    },
    {
      id: '455',
      rank: 3,
      type: 'stale',
      title: 'Stale PR #455',
      source: 'acme/billing',
      why: 'going cold — no reviewer activity',
      isHypothesis: false,
      chips: [{ label: 'idle 4d', tone: 'stale' }],
      actions: ['Nudge'],
    },
  ],
}

// Generic latency wrapper so every screen exercises its loading state.
function delay<T>(value: T, ms = 350): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

export function fetchToday(): Promise<TodayData> {
  return delay(TODAY, 450)
}

// ---- Work browser ----
const WORK: WorkRow[] = [
  { id: '482', type: 'pr', glyph: '⎇', title: '#482 · Checkout returns 500 under tier discount', status: 'review · mine', statusTone: 'warn', meta: ['wait 51h', 'acme/billing'], source: 'GitHub' },
  { id: '1893', type: 'build', glyph: '⚡', title: 'Build #1893 · test job failed', status: 'failed', statusTone: 'fail', meta: ['22m', 'feature/pricing'], source: 'GitHub' },
  { id: 'T91', type: 'task', glyph: '◆', title: 'TICKET-91 · Checkout 500s under discount', status: 'in progress', statusTone: 'info', meta: ['P1', 'Jira'], source: 'Jira' },
  { id: '455', type: 'stale', glyph: '⎇', title: '#455 · Refactor pricing tiers', status: 'stale', statusTone: 'stale', meta: ['idle 4d', 'acme/billing'], source: 'GitHub' },
  { id: 'evt1', type: 'review', glyph: '◷', title: 'Release planning · standup', status: 'today 15:00', statusTone: 'info', meta: ['Google'], source: 'Calendar' },
  { id: '478', type: 'pr', glyph: '⎇', title: '#478 · Add idempotency keys to webhooks', status: 'merged', statusTone: 'healthy', meta: ['yesterday', 'acme/core'], source: 'GitHub' },
]
export function fetchWork(): Promise<WorkRow[]> {
  return delay(WORK)
}

// ---- Build-failure analysis ----
const BUILD_1893: BuildFailure = {
  id: '1893',
  repo: 'acme/billing',
  branch: 'feature/pricing',
  pr: '482',
  run: '#1893',
  failedAgo: 'failed 22m ago',
  boundary: { mode: 'local', label: 'On your machine' },
  summary:
    'The test job failed on one assertion in PricingServiceTest. Your commit d4e5f6 is the only change touching that path — most likely a tier-boundary off-by-one.',
  summaryConfidence: 'high',
  failingJob: 'test',
  failingStep: './gradlew test',
  failingTest: 'PricingServiceTest.appliesTierDiscount',
  redacted: true,
  log: [
    { text: 'job: test → step: ./gradlew test', kind: 'omitted' },
    { text: 'test: PricingServiceTest.appliesTierDiscount' },
    { text: 'expected: 90.00 but was: 100.00        ← first failure', kind: 'fail' },
    { text: '  at PricingServiceTest.appliesTierDiscount(:211)' },
    { text: '[… 340 lines omitted …]', kind: 'omitted' },
    { text: '‹SECRET REDACTED: env token›', kind: 'redacted' },
  ],
  causes: [
    {
      rank: 1,
      text: 'Tier-boundary off-by-one in discount()',
      confidence: 'high',
      evidence: [
        { id: 'assert L211', boundary: 'local' },
        { id: 'commit d4e5f6', boundary: 'local' },
      ],
    },
    {
      rank: 2,
      text: 'Stale test fixture',
      confidence: 'low',
      uncited: true,
      evidence: [],
    },
  ],
  related: [
    { id: 'PR #482', boundary: 'local' },
    { id: 'commit d4e5f6', boundary: 'local' },
    { id: 'prior fail #1120', boundary: 'local' },
    { id: 'TICKET-91', boundary: 'local' },
  ],
  diagnostics: [
    'Run only the failing test locally.',
    'Inspect the tier boundary at qty=10.',
  ],
  fixes: ['Adjust the boundary check in discount() so qty=10 falls in the discounted tier.'],
}
export function fetchBuildFailure(id: string): Promise<BuildFailure> {
  return delay({ ...BUILD_1893, id })
}

// ---- Handoff ----
const HANDOFF: Handoff = {
  id: 'h1',
  title: 'Fix CI on feature/pricing',
  target: 'Claude Code',
  version: 1,
  boundary: { mode: 'local', label: 'On your machine' },
  rendered: `# Agent Handoff — Fix failing CI on feature/pricing
Repo: acme/billing @ feature/pricing  PR #482  Commits a1b2c3..d4e5f6
Failing: \`test\` → PricingServiceTest.appliesTierDiscount

## Reproduce
1. ./gradlew test --tests PricingServiceTest.appliesTierDiscount

## Evidence
- expected 90.00 but was 100.00 (log L211)
- commit d4e5f6 changed discount() — only change on this path

## Ranked hypotheses
1. (high) tier-boundary off-by-one in discount()
2. (low)  stale fixture — no evidence in diff

## Constraints & acceptance
- Fix must make the failing test pass without weakening assertions.
- Expected output: a diff + a passing test run.`,
  safety: {
    allow: ['Verify the fix with tests before claiming done', 'Allowed: read repo, ./gradlew test, edit src/tests'],
    forbid: ['Do NOT push / merge / deploy / delete without approval', 'Forbidden: network, deploy, destructive git'],
  },
  sources: [
    { id: '#482', boundary: 'local' },
    { id: 'd4e5f6', boundary: 'local' },
    { id: 'log (redacted)', boundary: 'local' },
    { id: 'TICKET-91', boundary: 'local' },
  ],
}
export function fetchHandoff(_id: string): Promise<Handoff> {
  return delay(HANDOFF)
}

// ---- Integrations ----
const INTEGRATIONS: Integration[] = [
  {
    key: 'github', name: 'GitHub', state: 'connected',
    detail: 'GitHub App · 6 repos · connected · 2m ago',
    scopes: ['Contents', 'Pull requests', 'Issues', 'Checks', 'Actions', 'Deployments'],
    actions: ['Manage repos', 'Re-sync', 'Disconnect'],
  },
  {
    key: 'jira', name: 'Jira', state: 'partial',
    detail: 'partial — rate limited',
    note: 'Last sync hit an Atlassian rate limit. Retrying automatically in ~3m.',
    actions: ['Retry now', 'Details'],
  },
  {
    key: 'gcal', name: 'Google Calendar', state: 'connected',
    detail: 'connected · read · 5m ago', actions: ['Re-sync', 'Disconnect'],
  },
  {
    key: 'mscal', name: 'Microsoft Calendar', state: 'not_connected',
    actions: ['Connect'],
  },
]
export function fetchIntegrations(): Promise<Integration[]> {
  return delay(INTEGRATIONS)
}

// ---- Providers ----
const PROVIDERS: ProvidersData = {
  local: {
    name: 'Ollama', defaultModel: 'Qwen3-Coder-30B-A3B', loaded: true,
    models: ['Qwen3-Coder-30B-A3B', 'gpt-oss-20b', 'Gemma 3 12B', 'Mistral Small 3.2 24B'],
  },
  anthropic: { name: 'Anthropic', boundaryLabel: 'leaves for Anthropic', hasKey: true, valid: true, capCents: 2000, usedCents: 640 },
  openai: { name: 'OpenAI', boundaryLabel: 'leaves for OpenAI', hasKey: false, note: 'Uses the Responses API · stateless' },
  fallbackOn: false,
}
export function fetchProviders(): Promise<ProvidersData> {
  return delay(PROVIDERS)
}

// ---- Privacy ----
const PRIVACY: PrivacyData = {
  defaultBoundary: 'Local-first — nothing leaves unless you add a key and approve it.',
  localOnlyRepos: ['acme/secret-svc', 'acme/payments'],
  egress: [
    { time: '09:02', action: 'build #1893 summary', to: 'Anthropic', tokens: '~2.1k tok' },
    { time: 'Mon', action: '(nothing left)', to: '', tokens: '' },
  ],
}
export function fetchPrivacy(): Promise<PrivacyData> {
  return delay(PRIVACY)
}

// ---- Brainstorm ----
const BRAINSTORM: BrainstormData = {
  sessions: [
    { id: 's1', title: 'Pricing refactor' },
    { id: 's2', title: 'Release plan' },
    { id: 's3', title: 'Idempotency notes' },
  ],
  active: {
    id: 's1',
    title: 'Pricing refactor',
    visibility: 'personal',
    model: 'Qwen3-Coder',
    boundary: { mode: 'local', label: 'all local' },
    inContext: [
      { id: 'PR #482', boundary: 'local' },
      { id: 'TICKET-91', boundary: 'local' },
      { id: 'build #1893', boundary: 'local' },
    ],
    messages: [
      { role: 'you', text: 'Should discount() be tier-aware or flat?' },
      {
        role: 'ai', model: 'Qwen3-Coder', hypothesis: true,
        text: 'Given TICKET-91 and the failing test at the tier boundary, tier-aware is safer — the 500 came from a flat calc ignoring the qty=10 threshold. Keep it tier-aware and add a boundary test.',
        sources: [
          { id: 'TICKET-91', boundary: 'local' },
          { id: 'build #1893', boundary: 'local' },
        ],
      },
    ],
  },
}
export function fetchBrainstorm(): Promise<BrainstormData> {
  return delay(BRAINSTORM)
}

// ---- Onboarding ----
const ONBOARDING: OnboardStep[] = [
  { n: '✓', title: 'Sign in', detail: 'via GitHub · sara.santos', state: 'done' },
  { n: '✓', title: 'Connect GitHub', detail: 'GitHub App · 6 repos · read-only', state: 'done' },
  { n: 3, title: 'Connect Jira & calendars', detail: 'Jira · Google Calendar · Microsoft Calendar', state: 'now', action: 'Connect' },
  { n: 4, title: 'Choose a model', detail: 'Local & free via Ollama — or add a key later', state: 'todo' },
  { n: 5, title: 'Mark local-only repos', detail: 'These never leave your machine', state: 'todo' },
]
export function fetchOnboarding(): Promise<OnboardStep[]> {
  return delay(ONBOARDING, 150)
}
