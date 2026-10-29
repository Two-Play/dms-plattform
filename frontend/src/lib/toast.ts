/**
 * Minimal toast store: short success or error notes after an action (saved, submitted, voted, ...).
 * The `Toaster` in the app shell renders them in a polite live region. No dependency needed.
 */

export type ToastVariant = 'success' | 'error'

export interface Toast {
  id: number
  message: string
  variant: ToastVariant
}

let toasts: readonly Toast[] = []
let nextId = 1
const listeners = new Set<() => void>()

function emit() {
  for (const listener of listeners) listener()
}

/** Shows a toast; returns its id. */
export function toast(message: string, variant: ToastVariant = 'success'): number {
  const id = nextId++
  // At most three at a time; the oldest goes first.
  toasts = [...toasts, { id, message, variant }].slice(-3)
  emit()
  return id
}

export function dismissToast(id: number): void {
  toasts = toasts.filter((t) => t.id !== id)
  emit()
}

/** Removes all toasts (tests). */
export function clearToasts(): void {
  toasts = []
  emit()
}

export function subscribeToasts(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function getToasts(): readonly Toast[] {
  return toasts
}
