import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, FileDown, Upload } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useApiClient } from '@/api/context'
import { extensionOf } from '@/api/attachments'
import type { DocumentVersionView } from '@/api/partners'
import { isOpenVersion } from '@/api/version-view'
import {
  CONVERTIBLE_EXTENSIONS,
  downloadFilled,
  downloadOriginal,
  fetchPreview,
  isFillableFile,
  isWordFile,
  saveWordValues,
  uploadOriginal,
  wordFieldsQuery,
  type UploadFile,
  type WordField,
} from '@/api/word'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { formatDateTime, formatFileSize } from '@/i18n/format'
import { toast } from '@/lib/toast'
import { WordFieldHelp } from './word-field-help'
import { WordFieldInput, WordFieldValue } from './word-field-input'

const convertible = new Set<string>(CONVERTIBLE_EXTENSIONS)

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/**
 * The content of a file document version (DMS-115): its original with download, upload of a new file while
 * the version is a draft, the fields of a Word document with their values, the filled Word document and a
 * PDF preview (the archived rendition once approved).
 */
export function FileContentPanel({
  documentId,
  version,
  editable,
  upload,
}: {
  documentId: string
  version: DocumentVersionView
  /** True while the version is a draft the caller may edit. */
  editable: boolean
  /** Replaceable in tests. */
  upload?: UploadFile<DocumentVersionView>
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const inputId = useId()
  const fileInput = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<number | undefined>(undefined)
  const [uploadError, setUploadError] = useState<string | undefined>(undefined)
  const file = version.file
  const send = upload ?? uploadOriginal(documentId)

  const download = useMutation({
    mutationFn: (kind: 'original' | 'filled') =>
      file
        ? kind === 'original'
          ? downloadOriginal(client, documentId, file)
          : downloadFilled(client, documentId, file)
        : Promise.resolve(),
  })

  const choose = async (chosen: File) => {
    const extension = extensionOf(chosen.name) ?? ''
    if (!convertible.has(extension)) {
      setUploadError(t('word.file.notConvertible', { extension }))
      return
    }
    setUploadError(undefined)
    setProgress(0)
    try {
      await send(chosen, (sent, total) => {
        setProgress(total > 0 ? Math.round((sent / total) * 100) : 0)
      })
      toast(t('word.file.uploaded'))
      void queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
    } catch (e) {
      setUploadError(t('word.file.uploadFailed', { message: message(e) }))
    } finally {
      setProgress(undefined)
    }
  }

  return (
    <div className="space-y-6" data-testid="file-content">
      <Card>
        <CardHeader>
          <CardTitle>{t('word.file.title')}</CardTitle>
          <CardDescription>
            {editable ? t('word.file.descriptionDraft') : t('word.file.description')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {file ? (
            <div className="space-y-1">
              <p className="font-medium break-all">{file.name}</p>
              <p className="text-muted-foreground">
                {formatFileSize(file.size)} ·{' '}
                {t('word.file.uploadedBy', { date: formatDateTime(file.uploadedAt) })}{' '}
                <UserName id={file.uploadedBy} />
              </p>
              {file.basedOn && <p className="text-muted-foreground">{t('word.file.takenOver')}</p>}
              <p className="font-mono text-xs break-all text-muted-foreground">
                {t('word.file.checksum', { sha: file.sha256 })}
              </p>
            </div>
          ) : (
            <p className="text-muted-foreground">{t('word.file.none')}</p>
          )}
          <div className="flex flex-wrap gap-2">
            {file && (
              <Button
                size="sm"
                variant="outline"
                disabled={download.isPending}
                onClick={() => {
                  download.mutate('original')
                }}
              >
                <Download className="size-4" aria-hidden />
                {t('word.file.downloadOriginal')}
              </Button>
            )}
            {file && isFillableFile(file.name) && (
              <Button
                size="sm"
                variant="outline"
                disabled={download.isPending}
                onClick={() => {
                  download.mutate('filled')
                }}
              >
                <FileDown className="size-4" aria-hidden />
                {isWordFile(file.name)
                  ? t('word.file.downloadWord')
                  : t('word.file.downloadFilled')}
              </Button>
            )}
            {editable && (
              <>
                <input
                  ref={fileInput}
                  id={inputId}
                  type="file"
                  className="sr-only"
                  aria-label={file ? t('word.file.replace') : t('word.file.upload')}
                  accept={CONVERTIBLE_EXTENSIONS.map((e) => `.${e}`).join(',')}
                  onChange={(event) => {
                    const chosen = event.target.files?.[0]
                    event.target.value = ''
                    if (chosen) void choose(chosen)
                  }}
                />
                <Button
                  size="sm"
                  disabled={progress !== undefined}
                  onClick={() => fileInput.current?.click()}
                >
                  <Upload className="size-4" aria-hidden />
                  {progress !== undefined
                    ? t('word.file.uploading', { percent: progress })
                    : file
                      ? t('word.file.replace')
                      : t('word.file.upload')}
                </Button>
              </>
            )}
          </div>
          {editable && <p className="text-xs text-muted-foreground">{t('word.file.accepted')}</p>}
          {uploadError && (
            <p role="alert" className="text-destructive">
              {uploadError}
            </p>
          )}
          {download.error && (
            <p role="alert" className="text-destructive">
              {t('word.file.downloadFailed', { message: download.error.message })}
            </p>
          )}
        </CardContent>
      </Card>
      {file && isFillableFile(file.name) && (
        <WordFieldsCard
          key={`${version.id}-${String(version.workingState)}`}
          documentId={documentId}
          version={version}
          editable={editable}
        />
      )}
      {file && <PdfPreview documentId={documentId} version={version} />}
    </div>
  )
}

/** The fields of the Word document: manual ones as inputs while editable, automatic ones read-only. */
function WordFieldsCard({
  documentId,
  version,
  editable,
}: {
  documentId: string
  version: DocumentVersionView
  editable: boolean
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const fields = useQuery(wordFieldsQuery(client, documentId, version.id))
  const [values, setValues] = useState<Record<string, string> | undefined>(undefined)
  const current =
    values ??
    Object.fromEntries(
      (fields.data?.fields ?? [])
        .filter((f) => f.source === 'Manual')
        .map((f) => [f.key, f.value ?? '']),
    )
  const canEdit = editable && (fields.data?.editable ?? false)
  const save = useMutation({
    mutationFn: () => saveWordValues(client, documentId, current),
    onSuccess: () => {
      toast(t('word.fields.saved'))
      setValues(undefined)
      void queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
    },
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('word.fields.title')}</CardTitle>
        <CardDescription>
          {canEdit ? t('word.fields.description') : t('word.fields.readOnly')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {fields.isPending && <LoadingState label={t('word.fields.loading')} rows={3} />}
        {fields.isError && (
          <ErrorState error={fields.error} onRetry={() => void fields.refetch()} />
        )}
        {(fields.data?.fields ?? []).length === 0 && (
          <p className="text-sm text-muted-foreground">{t('word.fields.none')}</p>
        )}
        {fields.data && (fields.data.fields ?? []).length > 0 && (
          <form
            className="space-y-3"
            onSubmit={(event) => {
              event.preventDefault()
              save.mutate()
            }}
          >
            {(fields.data.fields ?? []).map((field) => (
              <FieldRow
                key={field.key}
                field={field}
                value={current[field.key] ?? ''}
                editable={canEdit}
                onChange={(value) => {
                  setValues({ ...current, [field.key]: value })
                }}
              />
            ))}
            {canEdit && (
              <Button type="submit" size="sm" disabled={save.isPending}>
                {save.isPending ? t('word.fields.saving') : t('word.fields.save')}
              </Button>
            )}
            {save.error && (
              <p role="alert" className="text-sm text-destructive">
                {t('word.fields.failed', { message: save.error.message })}
              </p>
            )}
          </form>
        )}
        {canEdit && <WordFieldHelp />}
      </CardContent>
    </Card>
  )
}

function FieldRow({
  field,
  value,
  editable,
  onChange,
}: {
  field: WordField
  value: string
  editable: boolean
  onChange: (value: string) => void
}) {
  const id = useId()
  const manual = field.source === 'Manual'
  return (
    <div className="grid gap-1.5 sm:grid-cols-[12rem_minmax(0,1fr)] sm:items-start">
      <Label htmlFor={id} className="flex flex-wrap items-center gap-1.5 sm:pt-1.5">
        {field.label}
        {manual && field.required && (
          <span className="text-destructive" aria-label={t('word.fields.required')}>
            *
          </span>
        )}
        <Badge variant="outline" className="font-normal">
          {manual ? t('word.fields.manual') : t('word.fields.automatic')}
        </Badge>
      </Label>
      <div className="min-w-0 space-y-1">
        {manual && editable ? (
          <WordFieldInput id={id} field={field} value={value} onChange={onChange} />
        ) : (
          <div id={id} className="text-sm">
            <WordFieldValue field={field} value={manual ? value : field.value} />
          </div>
        )}
        {field.warning && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            {t('word.fields.warning', { message: field.warning })}
          </p>
        )}
      </div>
    </div>
  )
}

/** The PDF of the version, loaded on request (a conversion takes a few seconds). */
function PdfPreview({ documentId, version }: { documentId: string; version: DocumentVersionView }) {
  const client = useApiClient()
  const [shown, setShown] = useState(false)
  const preview = useQuery({
    queryKey: ['documents', documentId, 'versions', version.id, 'preview', version.workingState],
    queryFn: () => fetchPreview(client, documentId, version.id),
    enabled: shown,
    retry: false,
    staleTime: Infinity,
  })
  const url = useMemo(
    () =>
      preview.data && typeof URL.createObjectURL === 'function'
        ? URL.createObjectURL(preview.data)
        : undefined,
    [preview.data],
  )
  useEffect(
    () => () => {
      if (url) URL.revokeObjectURL(url)
    },
    [url],
  )

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
        <div className="space-y-1.5">
          <CardTitle>{t('word.file.preview')}</CardTitle>
          <CardDescription>
            {isOpenVersion(version) ? t('word.file.previewDraft') : t('word.file.previewApproved')}
          </CardDescription>
        </div>
        {!shown && (
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              setShown(true)
            }}
          >
            {t('word.file.previewLoad')}
          </Button>
        )}
      </CardHeader>
      {shown && (
        <CardContent>
          {preview.isPending && <LoadingState label={t('word.file.previewLoading')} rows={6} />}
          {preview.isError && (
            <p role="alert" className="text-sm text-destructive">
              {t('word.file.previewFailed', { message: preview.error.message })}
            </p>
          )}
          {url && (
            <iframe
              title={t('word.file.previewRegion', { label: version.version })}
              src={url}
              className="h-[75vh] w-full rounded-lg border"
              data-testid="file-preview"
            />
          )}
        </CardContent>
      )}
    </Card>
  )
}
