<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import { homePath } from '../router'
import AuthShell from '../components/AuthShell.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
const session = useSessionStore(); const route = useRoute(); const router = useRouter()
const displayName = ref(''); const password = ref(''); const newUser = ref(!session.me)
const { busy, error, run } = useTask()
async function submit() {
  await run(async () => {
    session.adopt(await api.acceptInvitation({ token: String(route.params.token), ...(newUser.value ? { displayName: displayName.value, password: password.value } : {}) }))
    await router.replace(homePath())
  })
  password.value = ''
}
</script>
<template><AuthShell title="接受組織邀請" description="接受邀請後即可加入組織。若邀請已失效，請聯絡組織管理員。"><form class="form-stack" @submit.prevent="submit"><label v-if="!session.me" class="check-label"><input v-model="newUser" type="checkbox" :disabled="busy">我是新使用者</label><template v-if="newUser"><label>顯示名稱<input v-model="displayName" autocomplete="name" required :disabled="busy"></label><label>設定密碼<input v-model="password" type="password" autocomplete="new-password" required :disabled="busy"></label></template><p v-else class="muted">既有使用者可直接接受邀請；伺服器將驗證邀請身分。</p><ErrorBanner :error="error" /><button class="primary" :disabled="busy">{{ busy ? '加入中…' : '接受邀請' }}</button></form><p><RouterLink :to="{ path: '/login', query: { next: route.path } }">使用既有帳號登入</RouterLink></p></AuthShell></template>

