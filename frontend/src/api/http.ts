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
  OnboardStep,
} from '../types'

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

export const fetchToday = () => get<TodayData>('/today')
export const fetchWork = () => get<WorkRow[]>('/work')
export const fetchBuildFailure = (id: string) => get<BuildFailure>(`/builds/${id}`)
export const fetchLatestBuild = () => get<BuildFailure>('/builds')
export const fetchHandoff = (id: string) => get<Handoff>(`/handoffs/${id}`)
export const fetchIntegrations = () => get<Integration[]>('/integrations')
export const fetchProviders = () => get<ProvidersData>('/providers')
export const setActiveModel = (name: string) => post<ProvidersData>('/providers/model', { name })
export const fetchPrivacy = () => get<PrivacyData>('/privacy')
export const fetchBrainstorm = () => get<BrainstormData>('/brainstorm')
export const sendBrainstorm = (
  message: string,
  sourceIds: string[],
  history: { role: string; text: string }[] = [],
) => post<BrainstormMessage>('/brainstorm/messages', { message, sourceIds, history })
export const fetchOnboarding = () => get<OnboardStep[]>('/onboarding')
export const syncSource = (source: string) =>
  post<{ source: string; ingested: number }>(`/integrations/${source}/sync`, {})
export const disconnectSource = (source: string) =>
  del<{ source: string; removed: number }>(`/integrations/${source}`)
