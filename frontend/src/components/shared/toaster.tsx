import { CircleAlert, CircleCheck, X } from 'lucide-react'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { cn } from 'cn'
import { dismissToast, getToasts, subscribeToasts, type Toast } from '@/lib/toast'

const texts = {
  region: 'Notifications',
  dismiss: 'Dismiss notification',
}

/** How long a toast stays; paused while the pointer or focus is on it. */
const DURATION_MS = 6000

function ToastItem({ toast }: { toast: Toast }) {
  const [paused, setPaused] = useState(false)
  const remaining = useRef(DURATION_MS)

  useEffect(() => {
    if (paused) return
    const started = Date.now()
    const timer = setTimeout(() => {
      dismissToast(toast.id)
    }, remaining.current)
    return () => {
      clearTimeout(timer)
      remaining.current = Math.max(1000, remaining.current - (Date.now() - started))
    }
  }, [paused, toast.id])

  const Icon = toast.variant === 'error' ? CircleAlert : CircleCheck
  return (
    <div
      className={cn(
        'pointer-events-auto flex items-start gap-3 rounded-lg border bg-popover p-3 text-sm text-popover-foreground shadow-lg',
        toast.variant === 'error' && 'border-destructive/50',
      )}
      onMouseEnter={() => {
        setPaused(true)
      }}
      onMouseLeave={() => {
        setPaused(false)
      }}
      onFocus={() => {
        setPaused(true)
      }}
      onBlur={() => {
        setPaused(false)
      }}
    >
      <Icon
        aria-hidden="true"
        className={cn(
          'mt-0.5 size-4 shrink-0',
          toast.variant === 'error' ? 'text-destructive' : 'text-primary',
        )}
      />
      <p className="min-w-0 flex-1 break-words">{toast.message}</p>
      <button
        type="button"
        aria-label={texts.dismiss}
        className="-m-1 inline-flex size-6 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        onClick={() => {
          dismissToast(toast.id)
        }}
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </div>
  )
}

/** Renders the toasts in a polite live region at the bottom of the screen. */
export function Toaster() {
  const toasts = useSyncExternalStore(subscribeToasts, getToasts, getToasts)
  return (
    <section
      aria-label={texts.region}
      className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex flex-col items-end gap-2 sm:left-auto sm:w-96"
    >
      <div role="status" aria-live="polite" className="flex w-full flex-col gap-2">
        {toasts.map((t) => (
          <ToastItem key={t.id} toast={t} />
        ))}
      </div>
    </section>
  )
}
