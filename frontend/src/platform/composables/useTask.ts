import { ref, onMounted, onUnmounted } from 'vue'
export function useTask() {
  const busy = ref(false)
  const error = ref<unknown>(null)
  async function run<T>(task: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) return
    busy.value = true; error.value = null
    try { return await task() } catch (e) { error.value = e } finally { busy.value = false }
  }
  return { busy, error, run }
}
// Recursive timeouts avoid overlapping requests; no timer survives a route change.
export function usePolling(task: () => Promise<unknown>, enabled: () => boolean, interval: number) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let alive = true
  const tick = async () => {
    if (!alive) return
    if (enabled()) await task()
    if (alive) timer = setTimeout(tick, interval)
  }
  onMounted(() => { timer = setTimeout(tick, interval) })
  onUnmounted(() => { alive = false; clearTimeout(timer) })
}

