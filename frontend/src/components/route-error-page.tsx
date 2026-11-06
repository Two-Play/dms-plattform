import { useRouter, type ErrorComponentProps } from '@tanstack/react-router'
import { RotateCw } from 'lucide-react'
import { useContext, useEffect } from 'react'
import { FullPageMessage } from '@/components/full-page-message'
import { InAppShell } from '@/components/in-app-shell'
import { TechnicalDetail } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { isChunkLoadError, reloadOnceForChunk } from '@/lib/chunk-reload'

function reloadPage() {
  window.location.reload()
}

/**
 * A route that failed to load or render. A chunk replaced by a deployment reloads the page once; if that
 * does not help, or for any other error, the user can reload or try again.
 */
export function RouteErrorPage({ error, reset }: ErrorComponentProps) {
  const router = useRouter()
  const inShell = useContext(InAppShell)
  const chunk = isChunkLoadError(error)

  useEffect(() => {
    if (isChunkLoadError(error)) reloadOnceForChunk(error, reloadPage)
  }, [error])

  const title = t(chunk ? 'errors.page.updateTitle' : 'errors.page.title')
  const text = t(chunk ? 'errors.page.updateText' : 'errors.page.text')
  const actions = (
    <div className="flex flex-wrap gap-2">
      <Button onClick={reloadPage}>
        <RotateCw aria-hidden="true" />
        {t('common.reload')}
      </Button>
      <Button
        variant="outline"
        onClick={() => {
          void router.invalidate()
          reset()
        }}
      >
        {t('common.retry')}
      </Button>
    </div>
  )

  if (inShell) {
    return (
      <div role="alert" className="space-y-4">
        <PageHeader title={title} description={text} />
        {actions}
        <TechnicalDetail error={error} />
      </div>
    )
  }
  return (
    <FullPageMessage title={title}>
      <p className="text-muted-foreground">{text}</p>
      {actions}
      <TechnicalDetail error={error} />
    </FullPageMessage>
  )
}
