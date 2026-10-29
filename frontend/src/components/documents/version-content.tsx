import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { versionHtmlQuery } from '@/api/version-view'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
// The document stylesheet shared with the PDF rendering (modules/templates, DMS-39).
import '@dms-document/document.css'

/**
 * The rendered content of a version (DMS-112): drafts and versions in review or approval with the draft
 * marking from their saved values, approved versions from their frozen package. The HTML comes from the
 * backend's renderer, which escapes all text, and is styled by the shared document stylesheet. The box
 * scrolls on its own so the metadata next to it stays in view.
 */
export function VersionContent({
  documentId,
  version,
}: {
  documentId: string
  version: DocumentVersionView
}) {
  const client = useApiClient()
  const content = useQuery(versionHtmlQuery(client, documentId, version.id))

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
        <div className="space-y-1.5">
          <CardTitle>{t('docs.view.content')}</CardTitle>
          {content.data && (
            <CardDescription>
              {content.data.draftMarking ? t('docs.view.draftHint') : t('docs.view.frozenHint')}
            </CardDescription>
          )}
        </div>
        {content.data?.draftMarking && (
          <Badge variant="destructive">{content.data.draftMarking}</Badge>
        )}
      </CardHeader>
      <CardContent>
        {content.isPending && <LoadingState label={t('docs.view.loading')} rows={8} />}
        {content.isError && (
          <ErrorState error={content.error} onRetry={() => void content.refetch()} />
        )}
        {content.data && (
          // A size container: the document stylesheet adapts to the width of the box, not the screen.
          // Focusable, so keyboard users can scroll it.
          <div
            role="region"
            aria-label={t('docs.view.region', { label: content.data.version })}
            tabIndex={0}
            className="@container max-h-[75vh] overflow-y-auto overflow-x-clip rounded-lg bg-muted p-2 focus-visible:outline-2 focus-visible:outline-ring md:p-4"
            data-testid="version-content"
            lang={content.data.language}
            dangerouslySetInnerHTML={{ __html: content.data.html }}
          />
        )}
      </CardContent>
    </Card>
  )
}
