<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { useStatusStore } from '../stores/status'
import { useTask } from '../composables/useTask'
import { safeNext } from '../router'
import AuthShell from '../components/AuthShell.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
const route = useRoute(); const router = useRouter(); const session = useSessionStore(); const status = useStatusStore()
const email = ref(''); const password = ref('')
const { busy, error, run } = useTask()
async function submit() {
  await run(async () => { await session.login(email.value, password.value); await router.replace(safeNext(route.query.next)) })
  password.value = ''
}
</script>
<template><AuthShell title="歡迎回到 Atlas" description="登入組織的知識工作空間。"><form class="form-stack" @submit.prevent="submit"><label>電子郵件<input v-model="email" type="email" autocomplete="username" required :disabled="busy"></label><label>密碼<input v-model="password" type="password" autocomplete="current-password" required :disabled="busy"></label><ErrorBanner :error="error" /><button class="primary" :disabled="busy">{{ busy ? '登入中…' : '登入' }}</button></form><p v-if="status.status?.registrationOpen"><RouterLink to="/register">建立帳號與組織</RouterLink></p></AuthShell></template>

