// API facade: real backend by default; set VITE_USE_STUB=true to use the offline stub
// (useful for frontend-only work with no backend running).
import * as stub from './stub'
import * as http from './http'

const useStub = import.meta.env.VITE_USE_STUB === 'true'
const api = useStub ? stub : http

export const fetchToday = api.fetchToday
export const fetchWork = api.fetchWork
export const fetchBuildFailure = api.fetchBuildFailure
export const fetchLatestBuild = api.fetchLatestBuild
export const fetchHandoff = api.fetchHandoff
export const fetchIntegrations = api.fetchIntegrations
export const fetchProviders = api.fetchProviders
export const setActiveModel = api.setActiveModel
export const fetchPrivacy = api.fetchPrivacy
export const fetchBrainstorm = api.fetchBrainstorm
export const fetchBrainstormSession = api.fetchBrainstormSession
export const createBrainstormSession = api.createBrainstormSession
export const sendBrainstorm = api.sendBrainstorm
export const fetchOnboarding = api.fetchOnboarding
export const syncSource = api.syncSource
export const disconnectSource = api.disconnectSource
