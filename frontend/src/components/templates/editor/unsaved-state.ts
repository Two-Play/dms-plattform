import { useEffect } from 'react'

/** How many mounted guards hold unsaved changes. */
let unsaved = 0

/**
 * Whether anything on the page has unsaved changes. For what the router's blocker does not see: switching
 * the language renders every page again from scratch (`App`), which empties forms without navigating.
 */
export function hasUnsavedChanges(): boolean {
  return unsaved > 0
}

/** Counts this component's unsaved changes while they exist. */
export function useUnsavedChanges(dirty: boolean): void {
  useEffect(() => {
    if (!dirty) return undefined
    unsaved += 1
    return () => {
      unsaved -= 1
    }
  }, [dirty])
}

/** Tells the parent whether the component has unsaved changes; `false` when it goes away. */
export function useReportDirty(dirty: boolean, onDirtyChange?: (dirty: boolean) => void): void {
  useEffect(() => {
    onDirtyChange?.(dirty)
  }, [dirty, onDirtyChange])
  useEffect(
    () => () => {
      onDirtyChange?.(false)
    },
    [onDirtyChange],
  )
}
