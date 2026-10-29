import { useMutation } from '@tanstack/react-query'
import { FileDown } from 'lucide-react'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { downloadVersionPdf, pdfKind } from '@/api/version-view'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'

/**
 * "Download PDF" for a version (DMS-112): the draft PDF of an open version (generated from its last saved
 * state, marked "Draft – not valid"), the archived PDF/A of an approved version, or – with "Attach change
 * history" – a copy of the approved version with the change history; the archived file stays unchanged.
 * Hidden for versions without a PDF (e.g. a withdrawn draft).
 */
export function PdfDownload({
  documentId,
  version,
}: {
  documentId: string
  version: DocumentVersionView
}) {
  const client = useApiClient()
  const checkboxId = useId()
  const attachmentsId = useId()
  const hintId = useId()
  const [changelog, setChangelog] = useState(false)
  const [attachments, setAttachments] = useState(false)
  const download = useMutation({
    mutationFn: () => downloadVersionPdf(client, documentId, version, changelog, attachments),
  })

  const kind = pdfKind(version, changelog || attachments)
  if (kind === undefined) return null
  const hint =
    kind === 'draft'
      ? t('docs.pdf.draftHint')
      : kind === 'copy'
        ? t('docs.pdf.copyHint')
        : t('docs.pdf.archivedHint')

  return (
    <div className="max-w-xs space-y-1.5 text-sm" data-testid="pdf-download">
      <Button
        size="sm"
        variant="outline"
        disabled={download.isPending}
        aria-describedby={hintId}
        onClick={() => {
          download.mutate()
        }}
      >
        <FileDown className="size-4" aria-hidden />
        {download.isPending
          ? t('docs.pdf.downloading')
          : kind === 'copy' && changelog
            ? t('docs.pdf.downloadWithHistory')
            : t('docs.pdf.download')}
      </Button>
      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id={checkboxId}
          className="size-4"
          checked={changelog}
          onChange={(event) => {
            setChangelog(event.target.checked)
          }}
        />
        <label htmlFor={checkboxId}>{t('docs.pdf.changelog')}</label>
      </div>
      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id={attachmentsId}
          className="size-4"
          checked={attachments}
          onChange={(event) => {
            setAttachments(event.target.checked)
          }}
        />
        <label htmlFor={attachmentsId}>{t('docs.pdf.attachmentList')}</label>
      </div>
      <p id={hintId} className="text-xs text-muted-foreground">
        {hint}
      </p>
      {download.error && (
        <p role="alert" className="text-destructive">
          {t('docs.pdf.failed', { message: download.error.message })}
        </p>
      )}
    </div>
  )
}
