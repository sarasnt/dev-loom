import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/today' },
  { path: '/today', name: 'today', component: () => import('../views/TodayView.vue') },
  { path: '/work', name: 'work', component: () => import('../views/WorkView.vue') },
  { path: '/builds', name: 'builds', component: () => import('../views/BuildFailureView.vue') },
  { path: '/builds/:id', name: 'build', component: () => import('../views/BuildFailureView.vue') },
  { path: '/handoffs/:id?', name: 'handoff', component: () => import('../views/HandoffView.vue') },
  { path: '/brainstorm', name: 'brainstorm', component: () => import('../views/BrainstormView.vue') },
  { path: '/repos', name: 'repos', component: () => import('../views/ReposView.vue') },
  { path: '/fleet', name: 'fleet', component: () => import('../views/FleetView.vue') },
  { path: '/onboarding', name: 'onboarding', component: () => import('../views/OnboardingView.vue') },
  { path: '/settings/integrations', name: 'integrations', component: () => import('../views/IntegrationsView.vue') },
  { path: '/settings/models', name: 'models', component: () => import('../views/ProvidersView.vue') },
  { path: '/settings/providers', redirect: '/settings/models' }, // old bookmark-friendly path
  { path: '/settings/capabilities', name: 'capabilities', component: () => import('../views/CapabilitiesView.vue') },
  { path: '/settings/general', name: 'general', component: () => import('../views/GeneralView.vue') },
  { path: '/settings/monitoring', name: 'monitoring', component: () => import('../views/MonitoringView.vue') },
  { path: '/settings/privacy', redirect: '/settings/monitoring' }, // privacy folded into Monitoring
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
