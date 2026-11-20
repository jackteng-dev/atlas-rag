import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError } from '../api/client'
import type { Me } from '../api/types'
const storageKey = 'atlas.lastOrgId'
function remembered() { try { return localStorage.getItem(storageKey) ?? '' } catch { return '' } }
export const useSessionStore = defineStore('session', () => {
  const me = ref<Me | null>(null)
  const currentOrgId = ref(remembered())
  const loaded = ref(false)
  const currentRole = computed(() => me.value?.memberships.find(m => m.orgId === currentOrgId.value)?.role ?? null)
  const isSystemAdmin = computed(() => me.value?.user.systemRole === 'SYSTEM_ADMIN')
  const canAdmin = computed(() => currentRole.value === 'OWNER' || currentRole.value === 'ADMIN')
  const canApprove = computed(() => canAdmin.value || currentRole.value === 'EDITOR')
  function switchOrg(orgId: string) {
    if (!me.value?.memberships.some(m => m.orgId === orgId)) return false
    currentOrgId.value = orgId
    try { localStorage.setItem(storageKey, orgId) } catch { /* Storage may be disabled. */ }
    return true
  }
  function adopt(value: Me) {
    me.value = value; loaded.value = true
    if (!value.memberships.some(m => m.orgId === currentOrgId.value)) currentOrgId.value = ''
    if (currentOrgId.value) switchOrg(currentOrgId.value)
    else if (value.memberships[0]) switchOrg(value.memberships[0].orgId)
  }
  async function load(force = false) {
    if (loaded.value && !force) return
    try { adopt(await api.me()) }
    catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e
      me.value = null; loaded.value = true
    }
  }
  async function login(email: string, password: string) { adopt(await api.login({ email, password })) }
  async function logout() {
    await api.logout(); me.value = null; currentOrgId.value = ''; loaded.value = true
    try { localStorage.removeItem(storageKey) } catch { /* Storage may be disabled. */ }
  }
  return { me, currentOrgId, currentRole, isSystemAdmin, canAdmin, canApprove, loaded, adopt, load, login, logout, switchOrg }
})

