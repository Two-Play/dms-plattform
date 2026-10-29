import { InlineError } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { toast } from '@/lib/toast'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2 } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { acknowledge, acknowledgementStateQuery, markOpened } from '@/api/acknowledgements'
import { useApiClient } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'

/**
 * Acknowledgement of a published version (DMS-47, ADR 0027). Displaying the card records that the
 * caller opened the version; "Acknowledge" is enabled once that is recorded and then shows the
 * acknowledgement with version and time.
 */
export function AcknowledgementPanel({
  documentId,
  version,
}: {
  documentId: string
  version: DocumentVersionView
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const state = useQuery(acknowledgementStateQuery(client, documentId, version.id))
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['acknowledgements'] })

  const opened = useMutation({
    mutationFn: () => markOpened(client, documentId, version.id),
    onSuccess: refresh,
  })
  const confirm = useMutation({
    mutationFn: () => acknowledge(client, documentId, version.id),
    onSuccess: async () => {
      toast(t('docPanels.acknowledgement.confirmed'))
      await refresh()
    },
  })

  // Record the opening once per displayed version; the backend keeps the first one.
  const recorded = useRef<string | undefined>(undefined)
  const { mutate: recordOpened } = opened
  useEffect(() => {
    if (recorded.current === version.id) return
    recorded.current = version.id
    recordOpened()
  }, [version.id, recordOpened])

  const acknowledgedAt = confirm.data?.acknowledgedAt ?? state.data?.acknowledgedAt
  const isOpened = opened.isSuccess || state.data?.opened === true
  const error = opened.error ?? confirm.error ?? state.error

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('docPanels.acknowledgement.title')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {state.isPending && (
          <LoadingState label={t('docPanels.acknowledgement.loading')} rows={1} />
        )}
        {acknowledgedAt ? (
          <p className="flex items-center gap-2" data-testid="acknowledged">
            <CheckCircle2 className="size-4 text-primary" aria-hidden />
            {t('docPanels.acknowledgement.done', {
              version: version.version,
              date: formatDateTime(acknowledgedAt),
            })}
          </p>
        ) : (
          state.data && (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-muted-foreground">
                {t('docPanels.acknowledgement.pleaseConfirm', { version: version.version })}
              </p>
              <Button
                size="sm"
                disabled={!isOpened || confirm.isPending}
                onClick={() => {
                  confirm.mutate()
                }}
              >
                {t('docPanels.acknowledgement.acknowledge')}
              </Button>
            </div>
          )
        )}
        {error && <InlineError error={error} />}
      </CardContent>
    </Card>
  )
}
