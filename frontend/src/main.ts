import { createApp } from 'vue'
import type { PlatformStatus } from './platform/api/types'

async function boot() {
  let status: PlatformStatus | undefined
  try {
    const response = await fetch('/api/status', { credentials: 'same-origin' })
    if (response.ok) {
      const body = await response.json()
      if (body.mode === 'PLATFORM') status = body
    }
  } catch { /* Legacy keeps its original connection error handling. */ }
  if (!status) {
    document.documentElement.classList.add('atlas-legacy')
    const [{ default: LegacyApp }] = await Promise.all([import('./legacy/LegacyApp.vue'), import('./style.css')])
    createApp(LegacyApp).mount('#app')
    return
  }
  document.documentElement.classList.add('atlas-platform')
  const [{ createPinia }, { default: App }, { router }, { useStatusStore }] = await Promise.all([
    import('pinia'), import('./platform/App.vue'), import('./platform/router'), import('./platform/stores/status'), import('./platform/styles/tokens.css'),
  ])
  const pinia = createPinia()
  const app = createApp(App).use(pinia)
  useStatusStore(pinia).status = status
  app.use(router)
  await router.isReady()
  app.mount('#app')
}
void boot()
