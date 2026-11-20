<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'
import { router, navigationError } from './router'
import Toast from './components/Toast.vue'
import ErrorBanner from './components/ErrorBanner.vue'
const error = ref<unknown>(null)
router.onError(e => { error.value = e })
router.afterEach(() => { error.value = null })
</script>
<template><div class="platform-root"><ErrorBanner :error="error || navigationError" /><RouterView /><Toast /></div></template>

