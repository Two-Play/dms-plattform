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

/**
 * Keeps unsaved changes from being lost (DMS-128): leaving the page inside the app asks first, closing
 * or reloading the tab triggers the browser's own warning.
 */
export function UnsavedChangesGuard({ dirty }: { dirty: boolean }) {
  const shouldBlockFn = useCallback(() => true, [])
  const blocker = useBlocker({
    shouldBlockFn,
    enableBeforeUnload: true,
    disabled: !dirty,
    withResolver: true,
  })
  return (
    <Dialog
      open={blocker.status === 'blocked'}
      onOpenChange={(open) => {
        if (!open) blocker.reset?.()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('templateEditor.unsaved.title')}</DialogTitle>
          <DialogDescription>{t('templateEditor.unsaved.text')}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => blocker.reset?.()}>
            {t('templateEditor.unsaved.stay')}
          </Button>
          <Button variant="destructive" onClick={() => blocker.proceed?.()}>
            {t('templateEditor.unsaved.leave')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
