import type { QueryClient } from '@tanstack/react-query'
import { createRouter, type RouterHistory } from '@tanstack/react-router'
import type { AuthContextProps } from 'react-oidc-context'
import { NotFoundPage } from '@/components/not-found-page'
import { routeTree } from './routeTree.gen'

/** Values every route can read in `beforeLoad`/`loader` and via `useRouteContext`. */
export interface RouterContext {
  auth: AuthContextProps
  queryClient: QueryClient
}

export function createAppRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    history,
    // `auth` is supplied by <RouterProvider context={{ auth }}> in App.
    context: { auth: undefined as unknown as AuthContextProps, queryClient },
    defaultPreload: 'intent',
    defaultNotFoundComponent: NotFoundPage,
    scrollRestoration: true,
  })
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>
  }
}
