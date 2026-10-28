import '@/index.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import { App } from '@/App'
import { createApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { getAccessToken, userManager } from '@/auth/oidc'
import { config } from '@/config'
import { createAppRouter } from '@/router'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 10_000, retry: 1 } },
})
const apiClient = createApiClient(config.apiBaseUrl, getAccessToken)
const router = createAppRouter(queryClient)

// Drop `code` and `state` from the URL once the callback is processed; the /callback route
// then forwards to the page the user originally asked for.
function onSigninCallback() {
  window.history.replaceState({}, document.title, window.location.pathname)
}

const root = document.getElementById('root')
if (!root) throw new Error('Missing #root element')

createRoot(root).render(
  <StrictMode>
    <AuthProvider userManager={userManager} onSigninCallback={onSigninCallback}>
      <QueryClientProvider client={queryClient}>
        <ApiClientProvider client={apiClient}>
          <App router={router} />
        </ApiClientProvider>
      </QueryClientProvider>
    </AuthProvider>
  </StrictMode>,
)
