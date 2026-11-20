import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../api/client'
import type { PlatformStatus } from '../api/types'
export const useStatusStore = defineStore('status', () => {
  const status = ref<PlatformStatus | null>(null)
  async function load() { status.value = await api.status() }
  return { status, load }
})

