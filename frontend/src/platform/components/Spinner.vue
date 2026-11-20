<script setup lang="ts">withDefaults(defineProps<{ label?: string }>(), { label: '載入中…' })</script>
<template><span class="loading" role="status"><span class="spinner" aria-hidden="true"></span>{{ label }}</span></template>

