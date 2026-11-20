<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import { homePath } from '../router'
import AuthShell from '../components/AuthShell.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
const session = useSessionStore(); const router = useRouter()
const form = ref({ email: '', password: '', displayName: '', orgName: '' })
const { busy, error, run } = useTask()
async function submit() {
  await run(async () => { session.adopt(await api.register(form.value)); await router.replace(homePath()) })
  form.value.password = ''
}
</script>
<template><AuthShell title="建立 Atlas 帳號"><form class="form-stack" @submit.prevent="submit"><label>電子郵件<input v-model="form.email" type="email" autocomplete="username" required :disabled="busy"></label><label>顯示名稱<input v-model="form.displayName" autocomplete="name" required :disabled="busy"></label><label>密碼<input v-model="form.password" type="password" autocomplete="new-password" required :disabled="busy"></label><label>組織名稱<input v-model="form.orgName" autocomplete="organization" required :disabled="busy"></label><ErrorBanner :error="error" /><button class="primary" :disabled="busy">{{ busy ? '建立中…' : '建立帳號' }}</button></form><p><RouterLink to="/login">返回登入</RouterLink></p></AuthShell></template>

