import { useBlocker } from '@tanstack/react-router'
import { useCallback } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { t } from '@/i18n'
import { useUnsavedChanges } from './unsaved-state'

/** Asks whether unsaved input may be dropped: staying is the safe choice, going on is marked destructive. */
export function DiscardDialog({
  open,
  title,
  text,
  stay,
  discard,
  onStay,
  onDiscard,
}: {
  open: boolean
  title: string
  text: string
  stay: string
  discard: string
  onStay: () => void
  onDiscard: () => void
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onStay()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{text}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onStay}>
            {stay}
          </Button>
          <Button variant="destructive" onClick={onDiscard}>
            {discard}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Keeps unsaved changes from being lost (DMS-128): leaving the page inside the app asks first, closing
 * or reloading the tab triggers the browser's own warning. The language switch asks too (DMS-165). A
 * navigation that follows from saving or discarding passes `ignoreBlocker`.
 */
export function UnsavedChangesGuard({ dirty }: { dirty: boolean }) {
  const shouldBlockFn = useCallback(() => true, [])
  const blocker = useBlocker({
    shouldBlockFn,
    enableBeforeUnload: true,
    disabled: !dirty,
    withResolver: true,
  })
  useUnsavedChanges(dirty)
  return (
    <DiscardDialog
      open={blocker.status === 'blocked'}
      title={t('templateEditor.unsaved.title')}
      text={t('templateEditor.unsaved.text')}
      stay={t('templateEditor.unsaved.stay')}
      discard={t('templateEditor.unsaved.leave')}
      onStay={() => blocker.reset?.()}
      onDiscard={() => blocker.proceed?.()}
    />
  )
}
