import type {
  TodayData,
  WorkRow,
  BuildFailure,
  Handoff,
  Integration,
  ProvidersData,
  PrivacyData,
  BrainstormData,
  BrainstormMessage,
  BrainstormSession,
  OnboardStep,
  SourceType,
  SourceView,
  RepoView,
  BrowseResult,
  RepoChanges,
} from '../types'

type SourceUpsert = {
  type?: string
  deployment?: string
  name?: string
  enabled?: boolean
  fields?: Record<string, string>
}

// Real backend client (SPEC §34). Base defaults to /api/v1 (proxied to :8080 in dev,
// same-origin behind nginx in the compose stack). Same function surface as the stub.
const BASE = (import.meta.env.VITE_API_BASE as string) ?? '/api/v1'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { headers: { Accept: 'application/json' } })
  if (!res.ok) {
    throw new Error(`GET ${path} → ${res.status}`)
  }
  return (await res.json()) as T
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    throw new Error(`POST ${path} → ${res.status}`)
  }
  return (await res.json()) as T
}

async function del<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'DELETE', headers: { Accept: 'application/json' } })
  if (!res.ok) {
    throw new Error(`DELETE ${path} → ${res.status}`)
  }
  return (await res.json()) as T
}

async function put<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    throw new Error(`PUT ${path} → ${res.status}`)
  }
  return (await res.json()) as T
}

export const fetchToday = () => get<TodayData>('/today')
export const fetchWork = () => get<WorkRow[]>('/work')
export const fetchBuildFailure = (id: string) => get<BuildFailure>(`/builds/${id}`)
export const fetchLatestBuild = () => get<BuildFailure>('/builds')
export const fetchHandoff = (id: string) => get<Handoff>(`/handoffs/${id}`)
export const fetchIntegrations = () => get<Integration[]>('/integrations')
export const fetchProviders = () => get<ProvidersData>('/providers')
export const setActiveModel = (name: string) => post<ProvidersData>('/providers/model', { name })
export const setProviderKey = (provider: string, key: string) =>
  post<ProvidersData>('/providers/keys', { provider, key })
export const clearProviderKey = (provider: string) =>
  del<ProvidersData>(`/providers/keys/${provider}`)
export const fetchPrivacy = () => get<PrivacyData>('/privacy')
export const fetchBrainstorm = () => get<BrainstormData>('/brainstorm')
export const fetchBrainstormSession = (id: string) =>
  get<BrainstormSession>(`/brainstorm/sessions/${id}`)
export const createBrainstormSession = (title?: string) =>
  post<BrainstormSession>('/brainstorm/sessions', { title: title ?? '' })
export const deleteBrainstormSession = (id: string) =>
  del<{ deleted: string }>(`/brainstorm/sessions/${id}`)
export const sendBrainstorm = (sessionId: string, message: string, sourceIds: string[]) =>
  post<BrainstormMessage>('/brainstorm/messages', { sessionId, message, sourceIds })
export const fetchOnboarding = () => get<OnboardStep[]>('/onboarding')
export const syncSource = (source: string) =>
  post<{ source: string; ingested: number }>(`/integrations/${source}/sync`, {})
export const disconnectSource = (source: string) =>
  del<{ source: string; removed: number }>(`/integrations/${source}`)

// ---- multi-source configuration ----
export const fetchSourceTypes = () => get<SourceType[]>('/source-types')
export const fetchSources = () => get<SourceView[]>('/sources')
export const createSource = (body: SourceUpsert) => post<SourceView>('/sources', body)
export const testSourceConfig = (body: SourceUpsert) =>
  post<{ ok: boolean; error?: string }>('/sources/test', body)
export const updateSource = (id: string, body: SourceUpsert) => put<SourceView>(`/sources/${id}`, body)
export const deleteSourceInstance = (id: string) => del<{ deleted: string }>(`/sources/${id}`)
export const syncSourceInstance = (id: string) =>
  post<{ id: string; ingested: number }>(`/sources/${id}/sync`, {})

// ---- local repositories (host agent) ----
export const fetchRepos = () => get<{ agentUp: boolean; repos: RepoView[] }>('/repos')
export const scanRepoFolder = (root: string) => post<RepoView[]>('/repos/scan', { root })
export const addRepoPath = (path: string) => post<RepoView[]>('/repos', { path })
export const removeRepo = (id: string) => del<{ removed: string }>(`/repos/${id}`)
export const setRepoIdentity = (id: string, name: string, email: string) =>
  put<RepoView>(`/repos/${id}/identity`, { name, email })
export const repoPull = (id: string) => post<{ ok: boolean; output: string }>(`/repos/${id}/pull`, {})
export const repoPush = (id: string) => post<{ ok: boolean; output: string }>(`/repos/${id}/push`, {})
export const repoPr = (id: string) =>
  post<{ ok: boolean; url?: string; web?: boolean; error?: string }>(`/repos/${id}/pr`, {})
export const browseFs = (path: string) => post<BrowseResult>('/fs/browse', { path })
export const repoChanges = (id: string) => post<RepoChanges>(`/repos/${id}/changes`, {})
export const repoStage = (id: string, files: string[]) =>
  post<{ ok: boolean; output: string }>(`/repos/${id}/stage`, { files })
export const repoUnstage = (id: string, files: string[]) =>
  post<{ ok: boolean; output: string }>(`/repos/${id}/unstage`, { files })
export const repoCommit = (id: string, message: string) =>
  post<{ ok: boolean; output: string }>(`/repos/${id}/commit`, { message })
