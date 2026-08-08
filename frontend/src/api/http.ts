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

export const fetchToday = () => get<TodayData>('/today')
export const fetchWork = () => get<WorkRow[]>('/work')
export const fetchBuildFailure = (id: string) => get<BuildFailure>(`/builds/${id}`)
export const fetchHandoff = (id: string) => get<Handoff>(`/handoffs/${id}`)
export const fetchIntegrations = () => get<Integration[]>('/integrations')
export const fetchProviders = () => get<ProvidersData>('/providers')
export const fetchPrivacy = () => get<PrivacyData>('/privacy')
export const fetchBrainstorm = () => get<BrainstormData>('/brainstorm')
export const fetchOnboarding = () => get<OnboardStep[]>('/onboarding')
