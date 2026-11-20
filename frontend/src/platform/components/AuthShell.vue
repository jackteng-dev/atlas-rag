<script setup lang="ts">defineProps<{ title: string; description?: string }>()</script>
<template><main class="auth-shell"><a class="auth-brand" href="/">Atlas<span>知識工作平台</span></a><section class="auth-card"><h1>{{ title }}</h1><p v-if="description" class="muted">{{ description }}</p><slot /></section></main></template>

