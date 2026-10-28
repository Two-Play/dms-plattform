import { AlertTriangle, Loader2 } from 'lucide-react'
import type { MouseEvent } from 'react'
import type { MissingValue, Preview, PreviewIssue } from '@/api/templates'
import { Badge } from '@/components/ui/badge'
// The document stylesheet shared with the PDF rendering (modules/templates, DMS-39).
import '@dms-document/document.css'

/** Label of the draft marking (business concept US-03.2). */
export const DRAFT_LABEL = 'Draft – not valid'

interface DocumentPreviewProps {
  preview: Preview | undefined
  /** A newer preview is being fetched. */
  updating: boolean
  error: Error | null
  /** Called with a field key when the user clicks a missing value. */
  onFocusField: (key: string) => void
}

function MissingList({
  missing,
  onFocusField,
}: {
  missing: MissingValue[]
  onFocusField: (key: string) => void
}) {
  if (missing.length === 0) return null
  return (
    <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-3 text-sm">
      <p className="font-medium text-destructive">
        {missing.length === 1
          ? '1 required value is missing'
          : `${missing.length} required values are missing`}
      </p>
      <ul className="mt-1 flex flex-wrap gap-1.5">
        {missing.map((m) => (
          <li key={m.field}>
            <button
              type="button"
              className="rounded border border-destructive/40 px-1.5 py-0.5 text-xs text-destructive hover:bg-destructive/10"
              onClick={() => {
                onFocusField(m.field)
              }}
            >
              {m.label}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function TemplateIssues({ issues }: { issues: PreviewIssue[] }) {
  const general = issues.filter((i) => i.field === undefined)
  if (general.length === 0) return null
  return (
    <div
      role="alert"
      className="rounded-lg border border-destructive/40 p-3 text-sm text-destructive"
    >
      {general.map((i) => (
        <p key={`${i.code}:${i.message}`}>{i.message}</p>
      ))}
    </div>
  )
}

/**
 * Live preview of the document being filled in. The HTML comes from the backend's renderer, which escapes all
 * text; it is styled by the shared document stylesheet. Clicking a marked missing value focuses its field.
 */
export function DocumentPreview({ preview, updating, error, onFocusField }: DocumentPreviewProps) {
  const onClick = (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target instanceof Element ? event.target.closest('[data-field]') : null
    const path = target?.getAttribute('data-field')
    if (target?.classList.contains('dms-missing') && path) {
      onFocusField(path.split('.')[0] ?? path)
    }
  }

  return (
    <section aria-label="Preview" className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="font-heading text-sm font-semibold">Preview</h2>
        <div className="flex items-center gap-2">
          {updating && (
            <span className="flex items-center gap-1 text-xs text-muted-foreground" role="status">
              <Loader2 className="size-3 animate-spin" aria-hidden="true" />
              Updating…
            </span>
          )}
          <Badge variant="destructive">{DRAFT_LABEL}</Badge>
        </div>
      </div>
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-destructive">
          <AlertTriangle className="size-4" aria-hidden="true" />
          {error.message}
        </p>
      )}
      {preview && (
        <>
          <MissingList missing={preview.missingRequired ?? []} onFocusField={onFocusField} />
          <TemplateIssues issues={preview.issues ?? []} />
          {preview.html && (
            // Mouse shortcut only: keyboard users reach the same fields through the list of missing values.
            // A size container: the document stylesheet adapts to the width of the preview, not the screen.
            // overflow-x-clip instead of overflow-auto, so the draft watermark can stick while scrolling.
            <div
              className="@container overflow-x-clip rounded-lg bg-muted p-2 md:p-4"
              data-testid="document-preview"
              onClick={onClick}
              dangerouslySetInnerHTML={{ __html: preview.html }}
            />
          )}
        </>
      )}
    </section>
  )
}
