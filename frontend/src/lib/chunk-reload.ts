/** Messages of a failed dynamic import in Chromium, Firefox and Safari, and of Vite's CSS preload. */
const CHUNK_LOAD_MESSAGE =
  /failed to fetch dynamically imported module|error loading dynamically imported module|importing a module script failed|unable to preload css/i

/** A code chunk could not be loaded, usually because a deployment replaced the file. */
export function isChunkLoadError(error: unknown): error is Error {
  return error instanceof Error && CHUNK_LOAD_MESSAGE.test(error.message)
}

/**
 * Reloads the page for a failed chunk, once per chunk and session, so a chunk that stays missing cannot
 * loop. The key is the one `lazyRouteComponent` uses for its own reload: a route component that already
 * reloaded for this file does not reload again. Without session storage there is no reload, because
 * nothing would stop the next one.
 */
export function reloadOnceForChunk(error: Error, reload: () => void): boolean {
  const key = `tanstack_router_reload:${error.message}`
  try {
    if (sessionStorage.getItem(key)) return false
    sessionStorage.setItem(key, '1')
  } catch {
    return false
  }
  reload()
  return true
}
