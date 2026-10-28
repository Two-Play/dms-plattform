import { createContext, use, type ReactNode } from 'react'
import type { ApiClient } from './client'

const ApiClientContext = createContext<ApiClient | null>(null)

export function ApiClientProvider({
  client,
  children,
}: {
  client: ApiClient
  children: ReactNode
}) {
  return <ApiClientContext value={client}>{children}</ApiClientContext>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useApiClient(): ApiClient {
  const client = use(ApiClientContext)
  if (!client) throw new Error('useApiClient must be used inside ApiClientProvider')
  return client
}
