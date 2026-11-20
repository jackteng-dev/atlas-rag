import { ref } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from './stores/session'
import { useStatusStore } from './stores/status'
import { api } from './api/client'
import AppLayout from './layouts/AppLayout.vue'
export const navigationError = ref<unknown>(null)
const pages = {
  overview: () => import('./pages/OverviewPage.vue'), chat: () => import('./pages/ChatPage.vue'),
  'knowledge-bases': () => import('./pages/KnowledgeBasesPage.vue'), documents: () => import('./pages/DocumentsPage.vue'),
  agents: () => import('./pages/AgentsPage.vue'), approvals: () => import('./pages/ApprovalsPage.vue'),
  connectors: () => import('./pages/ConnectorsPage.vue'), evaluation: () => import('./pages/EvaluationPage.vue'),
  usage: () => import('./pages/UsagePage.vue'), audit: () => import('./pages/AuditPage.vue'), settings: () => import('./pages/SettingsPage.vue'),
}
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./pages/LoginPage.vue'), meta: { public: true } },
    { path: '/register', component: () => import('./pages/RegisterPage.vue'), meta: { public: true } },
    { path: '/invite/:token', component: () => import('./pages/AcceptInvitationPage.vue'), meta: { public: true } },
    { path: '/', component: AppLayout, children: [
      { path: '', component: () => import('./pages/OverviewPage.vue') },
      { path: 'orgs/new', component: () => import('./pages/CreateOrgPage.vue') },
      { path: 'admin', component: () => import('./pages/AdminPage.vue'), meta: { admin: true } },
      { path: 'forbidden', component: () => import('./pages/ForbiddenPage.vue') },
      ...Object.entries(pages).map(([path, component]) => ({
        path: 'o/:orgId/' + path + (path === 'chat' ? '/:conversationId?' : ''), component,
        meta: { org: true, manage: ['connectors', 'usage', 'audit', 'settings'].includes(path), approvals: path === 'approvals' },
      })),
      { path: 'o/:orgId/knowledge-bases/:kbId', component: () => import('./pages/KnowledgeBaseDetailPage.vue'), meta: { org: true } },
      { path: 'o/:orgId/documents/:docId', component: () => import('./pages/DocumentDetailPage.vue'), meta: { org: true } },
      { path: 'o/:orgId/agents/:agentId', component: () => import('./pages/AgentDetailPage.vue'), meta: { org: true } },
      { path: 'o/:orgId/runs/:runId', component: () => import('./pages/RunDetailPage.vue'), meta: { org: true } },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
export function homePath() {
  const session = useSessionStore()
  return session.currentOrgId ? '/o/' + encodeURIComponent(session.currentOrgId) + '/overview' : '/orgs/new'
}
export function safeNext(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\') && !/^\/(login|register)([/?#]|$)/.test(value) ? value : homePath()
}
router.beforeEach(async to => {
  if (to.path === '/register' && !useStatusStore().status?.registrationOpen) return '/login'
  const session = useSessionStore()
  try { await api.csrf(); await session.load(true); navigationError.value = null }
  catch (e) {
    navigationError.value = e
    if (to.meta.public) return true
    return { path: '/login', query: { next: to.fullPath } }
  }

  if (!to.meta.public && !session.me) return { path: '/login', query: { next: to.fullPath } }
  if (to.path === '/') return homePath()
  if (to.meta.admin && !session.isSystemAdmin) return '/forbidden'
  if (to.meta.org) {
    const orgId = String(to.params.orgId)
    if (!session.me?.memberships.some(m => m.orgId === orgId)) {
      const first = session.me?.memberships[0]
      return first ? '/o/' + encodeURIComponent(first.orgId) + '/overview' : '/orgs/new'
    }
    session.switchOrg(orgId)
    if ((to.meta.manage && !session.canAdmin) || (to.meta.approvals && !session.canApprove)) return '/forbidden'
  }
})



