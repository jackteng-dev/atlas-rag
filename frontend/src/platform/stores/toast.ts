import { ref } from 'vue'
import { defineStore } from 'pinia'
export const useToastStore = defineStore('toast', () => {
  const items = ref<{ id: number; message: string }[]>([])
  let next = 0
  function remove(id: number) { items.value = items.value.filter(t => t.id !== id) }
  function show(message: string) {
    const id = ++next; items.value.push({ id, message })
    window.setTimeout(() => remove(id), 6000)
  }
  return { items, show, remove }
})

