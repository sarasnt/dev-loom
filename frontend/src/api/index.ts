// API facade: real backend by default; set VITE_USE_STUB=true to use the offline stub
// (useful for frontend-only work with no backend running).
import * as stub from './stub'
import * as http from './http'

const useStub = import.meta.env.VITE_USE_STUB === 'true'
const api = useStub ? stub : http

export const fetchToday = api.fetchToday
export const snoozeToday = api.snoozeToday
export const fetchWork = api.fetchWork
export const fetchBuildFailure = api.fetchBuildFailure
export const fetchLatestBuild = api.fetchLatestBuild
export const fetchHandoff = api.fetchHandoff
export const fetchIntegrations = api.fetchIntegrations
export const fetchProviders = api.fetchProviders
export const fetchMonitoring = api.fetchMonitoring
export const openBrainstormTerminal = api.openBrainstormTerminal
export const fetchInstalledModels = api.fetchInstalledModels
export const removeModel = api.removeModel
export const fetchSettings = api.fetchSettings
export const saveTerminalWorkdir = api.saveTerminalWorkdir
export const setActiveModel = api.setActiveModel
export const setProviderKey = api.setProviderKey
export const clearProviderKey = api.clearProviderKey
export const fetchPrivacy = api.fetchPrivacy
export const fetchBrainstorm = api.fetchBrainstorm
export const fetchBrainstormSession = api.fetchBrainstormSession
export const createBrainstormSession = api.createBrainstormSession
export const renameBrainstormSession = api.renameBrainstormSession
export const fetchRepoSessions = api.fetchRepoSessions
export const deleteBrainstormSession = api.deleteBrainstormSession
export const addBrainstormContext = api.addBrainstormContext
export const removeBrainstormContext = api.removeBrainstormContext
export const sendBrainstorm = api.sendBrainstorm
export const fetchOnboarding = api.fetchOnboarding
export const syncSource = api.syncSource
export const disconnectSource = api.disconnectSource
export const fetchSourceTypes = api.fetchSourceTypes
export const fetchSources = api.fetchSources
export const createSource = api.createSource
export const testSourceConfig = api.testSourceConfig
export const updateSource = api.updateSource
export const deleteSourceInstance = api.deleteSourceInstance
export const syncSourceInstance = api.syncSourceInstance
export const fetchRepos = api.fetchRepos
export const scanRepoFolder = api.scanRepoFolder
export const addRepoPath = api.addRepoPath
export const removeRepo = api.removeRepo
export const setRepoIdentity = api.setRepoIdentity
export const setRepoLocalOnly = api.setRepoLocalOnly
export const repoPull = api.repoPull
export const repoPush = api.repoPush
export const repoPr = api.repoPr
export const browseFs = api.browseFs
export const repoChanges = api.repoChanges
export const repoStage = api.repoStage
export const repoUnstage = api.repoUnstage
export const repoCommit = api.repoCommit
export const repoBranches = api.repoBranches
export const repoCheckout = api.repoCheckout
