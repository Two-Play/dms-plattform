import { useMutation } from '@tanstack/react-query'
import { FileDown, FileText } from 'lucide-react'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import type { DocumentVersionView } from '@/api/partners'
import { downloadVersionDocx, downloadVersionPdf, pdfKind } from '@/api/version-view'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { InlineError } from '@/components/shared/error-state'
import { errorText } from '@/components/shared/error-messages'

/**
 * "Download PDF" for a version (DMS-112): the draft PDF of an open version (generated from its last saved
 * state, marked "Draft – not valid"), the archived PDF/A of an approved version, or – with "Attach change
 * history" – a copy of the approved version with the change history; the archived file stays unchanged.
 * Hidden for versions without a PDF (e.g. a withdrawn draft). With `wordExport` (structured documents,
 * DMS-121) also "Download as Word": the same page converted into a Word document, with the same options.
 * PDFs generated on demand are limited (DMS-118): a refused download says when to try again.
 */
function failure(error: Error): string {
  return error instanceof ApiRequestError &&
    error.status === 429 &&
    error.retryAfterSeconds !== undefined
    ? t('docs.pdf.limited', { seconds: error.retryAfterSeconds })
    : t('docs.pdf.failed', { message: errorText(error) })
}

export function PdfDownload({
  documentId,
  version,
  wordExport = false,
}: {
  documentId: string
  version: DocumentVersionView
  /** Offer the Word export (structured documents; a file document's Word file is its filled original). */
  wordExport?: boolean
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
  const word = useMutation({
    mutationFn: () => downloadVersionDocx(client, documentId, version, changelog, attachments),
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
      {wordExport && (
        <Button
          size="sm"
          variant="outline"
          disabled={word.isPending}
          aria-describedby={hintId}
          onClick={() => {
            word.mutate()
          }}
        >
          <FileText className="size-4" aria-hidden />
          {word.isPending ? t('docs.pdf.downloadingWord') : t('docs.pdf.downloadWord')}
        </Button>
      )}
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
        <span>{hint}</span>
        {wordExport && <span className="block">{t('docs.pdf.wordHint')}</span>}
      </p>
      {download.error && <InlineError error={download.error} text={failure(download.error)} />}
      {word.error && (
        <InlineError
          error={word.error}
          text={t('docs.pdf.wordFailed', { message: errorText(word.error) })}
        />
      )}
    </div>
  )
}
