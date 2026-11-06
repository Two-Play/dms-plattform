import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import type { ReactNode } from 'react'

/**
 * A router that shows `page` at `/` and an empty page at `/elsewhere`, for components that need routing
 * (the unsaved changes guard) in a test that does not need the whole application.
 */
export function routed(page: ReactNode) {
  const root = createRootRoute({ component: Outlet })
  const router = createRouter({
    routeTree: root.addChildren([
      createRoute({ getParentRoute: () => root, path: '/', component: () => page }),
      createRoute({ getParentRoute: () => root, path: '/elsewhere', component: () => 'Elsewhere' }),
    ]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  return { router, element: <RouterProvider router={router} /> }
}
