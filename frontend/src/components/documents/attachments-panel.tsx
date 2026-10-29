import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Download,
  File,
  FileArchive,
  FileImage,
  FileSpreadsheet,
  FileText,
  Paperclip,
  Pencil,
  Trash2,
  Upload,
  type LucideIcon,
} from 'lucide-react'
import { useId, useRef, useState, type DragEvent, type SyntheticEvent } from 'react'
import {
  attachmentsQuery,
  BLOCKED_EXTENSIONS,
  downloadAttachment,
  extensionOf,
  fileKind,
  removeAttachment,
  renameAttachment,
  uploadAttachment,
  type Attachment,
  type FileKind,
  type UploadAttachment,
} from '@/api/attachments'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { t } from '@/i18n'
import { formatDateTime, formatFileSize } from '@/i18n/format'
import { toast } from '@/lib/toast'

const icons: Record<FileKind, LucideIcon> = {
  pdf: FileText,
  text: FileText,
  image: FileImage,
  spreadsheet: FileSpreadsheet,
  archive: FileArchive,
  other: File,
}

/** An upload in progress or one that failed, shown above the list until it is done. */
interface PendingUpload {
  key: number
  name: string
  sent: number
  total: number
  error?: string
}

/** Why a file is not sent at all: too large or a blocked type (the backend checks again). */
function rejection(file: File, maxSize: number): string | undefined {
  if (file.size === 0) return t('docPanels.attachments.empty')
  if (file.size > maxSize)
    return t('docPanels.attachments.tooLarge', { max: formatFileSize(maxSize) })
  const extension = extensionOf(file.name)
  if (extension && BLOCKED_EXTENSIONS.has(extension)) {
    return t('docPanels.attachments.blockedType', { extension })
  }
  return undefined
}

/** The user-facing reason a request failed, with the backend's message for the known codes. */
function uploadError(error: unknown, maxSize: number): string {
  if (error instanceof ApiRequestError) {
    switch (error.code) {
      case 'attachment_too_large':
        return t('docPanels.attachments.tooLarge', { max: formatFileSize(maxSize) })
      case 'attachment_blocked':
        return t('docPanels.attachments.blocked', { message: error.message })
      case 'attachment_infected':
        return t('docPanels.attachments.infected')
      case 'scanner_unavailable':
        return t('docPanels.attachments.scannerUnavailable')
      default:
        if (error.status === 409)
          return t('docPanels.attachments.conflict', { message: error.message })
        return error.message
    }
  }
  return t('docPanels.attachments.uploadFailed')
}

/**
 * "Anlagen" of a version (DMS-114): files reviewed, approved and archived together with the
 * document. While the version is a draft and the caller may edit it, files are added by drag and
 * drop or the file picker (with progress), renamed and removed; afterwards the list is read-only.
 * Everyone who may read the version sees and downloads the attachments.
 */
export function AttachmentsPanel({
  documentId,
  versionId,
  upload = uploadAttachment,
}: {
  documentId: string
  versionId: string
  upload?: UploadAttachment
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const query = attachmentsQuery(client, documentId, versionId)
  const attachments = useQuery(query)
  const [pending, setPending] = useState<PendingUpload[]>([])
  const [dragging, setDragging] = useState(false)
  const [renaming, setRenaming] = useState<Attachment | undefined>(undefined)
  const [removing, setRemoving] = useState<Attachment | undefined>(undefined)
  const input = useRef<HTMLInputElement>(null)
  const nextKey = useRef(1)
  const hintId = useId()

  const maxSize = attachments.data?.maxSizeBytes ?? 0
  const editable = attachments.data?.editable ?? false

  const refresh = () => queryClient.invalidateQueries({ queryKey: query.queryKey })

  const download = useMutation({
    mutationFn: (attachment: Attachment) => downloadAttachment(client, documentId, attachment),
  })

  function update(key: number, change: Partial<PendingUpload>) {
    setPending((current) => current.map((p) => (p.key === key ? { ...p, ...change } : p)))
  }

  async function send(files: File[]) {
    // One after the other: the backend checks names and contents against what is attached already.
    for (const file of files) {
      const key = nextKey.current++
      const refused = rejection(file, maxSize)
      setPending((current) => [
        ...current,
        { key, name: file.name, sent: 0, total: file.size, error: refused },
      ])
      if (refused) continue
      try {
        await upload(documentId, file, (sent, total) => {
          update(key, { sent, total })
        })
        setPending((current) => current.filter((p) => p.key !== key))
        toast(t('docPanels.attachments.added', { name: file.name }))
        await refresh()
      } catch (error) {
        update(key, { error: uploadError(error, maxSize) })
      }
    }
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (!editable) return
    void send(Array.from(event.dataTransfer.files))
  }

  const list = attachments.data?.attachments ?? []
  return (
    <Card data-testid="attachments">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Paperclip className="size-4" aria-hidden />
          {t('docPanels.attachments.title')}
        </CardTitle>
        <CardDescription>
          {editable
            ? t('docPanels.attachments.descriptionDraft')
            : t('docPanels.attachments.description')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {attachments.isPending && (
          <LoadingState label={t('docPanels.attachments.loading')} rows={2} />
        )}
        {attachments.isError && (
          <ErrorState error={attachments.error} onRetry={() => void attachments.refetch()} />
        )}
        {attachments.data && editable && (
          <div
            data-testid="attachment-drop-zone"
            onDragOver={(event) => {
              event.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => {
              setDragging(false)
            }}
            onDrop={onDrop}
            className={
              'flex flex-col items-center gap-2 rounded-lg border border-dashed px-4 py-6 text-center text-sm ' +
              (dragging ? 'border-primary bg-primary/5' : '')
            }
          >
            <Upload className="size-6 text-muted-foreground" aria-hidden />
            <p>{t('docPanels.attachments.dropHere')}</p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              aria-describedby={hintId}
              onClick={() => input.current?.click()}
            >
              {t('docPanels.attachments.choose')}
            </Button>
            <input
              ref={input}
              type="file"
              multiple
              className="sr-only"
              tabIndex={-1}
              aria-hidden
              data-testid="attachment-input"
              onChange={(event) => {
                const files = Array.from(event.target.files ?? [])
                event.target.value = ''
                void send(files)
              }}
            />
            <p id={hintId} className="text-muted-foreground">
              {t('docPanels.attachments.limits', { max: formatFileSize(maxSize) })}
            </p>
          </div>
        )}
        {pending.length > 0 && (
          <ul
            aria-label={t('docPanels.attachments.uploads')}
            className="space-y-2"
            aria-live="polite"
          >
            {pending.map((p) => (
              <li key={p.key} className="rounded-md border p-3 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span className="min-w-0 truncate font-medium">{p.name}</span>
                  {p.error ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setPending((current) => current.filter((x) => x.key !== p.key))
                      }}
                    >
                      {t('common.close')}
                    </Button>
                  ) : (
                    <span className="text-muted-foreground">
                      {formatFileSize(p.sent)} / {formatFileSize(p.total)}
                    </span>
                  )}
                </div>
                {p.error ? (
                  <p role="alert" className="text-destructive">
                    {p.error}
                  </p>
                ) : (
                  <progress
                    className="mt-2 h-2 w-full"
                    max={Math.max(p.total, 1)}
                    value={p.sent}
                    aria-label={t('docPanels.attachments.progress', { name: p.name })}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
        {attachments.data && list.length === 0 && (
          <EmptyState
            icon={Paperclip}
            title={t('docPanels.attachments.none')}
            description={editable ? t('docPanels.attachments.noneDraft') : undefined}
          />
        )}
        {list.length > 0 && (
          <ul aria-label={t('docPanels.attachments.list')} className="divide-y rounded-md border">
            {list.map((a) => {
              const Icon = icons[fileKind(a.contentType, a.name)]
              return (
                <li key={a.id} className="flex flex-wrap items-center gap-3 p-3 text-sm">
                  <Icon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
                  <div className="min-w-0 flex-1 space-y-0.5">
                    <p className="truncate font-medium">{a.name}</p>
                    <p className="text-muted-foreground">
                      {formatFileSize(a.size)} · {a.contentType} ·{' '}
                      {t('docPanels.attachments.addedBy')} <UserName id={a.addedBy} /> ·{' '}
                      {formatDateTime(a.addedAt)}
                    </p>
                    <p
                      className="truncate font-mono text-xs text-muted-foreground"
                      title={a.sha256}
                    >
                      SHA-256 {a.sha256}
                    </p>
                  </div>
                  <div className="flex gap-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      aria-label={t('docPanels.attachments.downloadLabel', { name: a.name })}
                      disabled={download.isPending}
                      onClick={() => {
                        download.mutate(a)
                      }}
                    >
                      <Download className="size-4" aria-hidden />
                      {t('docPanels.attachments.download')}
                    </Button>
                    {editable && (
                      <>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          aria-label={t('docPanels.attachments.renameLabel', { name: a.name })}
                          onClick={() => {
                            setRenaming(a)
                          }}
                        >
                          <Pencil className="size-4" aria-hidden />
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          aria-label={t('docPanels.attachments.removeLabel', { name: a.name })}
                          onClick={() => {
                            setRemoving(a)
                          }}
                        >
                          <Trash2 className="size-4" aria-hidden />
                        </Button>
                      </>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        )}
        {download.error && <InlineError error={download.error} />}
        {attachments.data && !editable && list.length > 0 && (
          <p className="text-sm text-muted-foreground">{t('docPanels.attachments.readOnly')}</p>
        )}
      </CardContent>
      {renaming && (
        <RenameDialog
          key={renaming.id}
          attachment={renaming}
          onClose={() => {
            setRenaming(undefined)
          }}
          onSave={async (name) => {
            await renameAttachment(client, documentId, renaming.id, name)
            toast(t('docPanels.attachments.renamed', { name }))
            setRenaming(undefined)
            await refresh()
          }}
        />
      )}
      <Dialog
        open={removing !== undefined}
        onOpenChange={(open) => {
          if (!open) setRemoving(undefined)
        }}
      >
        {removing && (
          <RemoveDialogContent
            attachment={removing}
            onRemove={async () => {
              await removeAttachment(client, documentId, removing.id)
              toast(t('docPanels.attachments.removed', { name: removing.name }))
              setRemoving(undefined)
              await refresh()
            }}
            onCancel={() => {
              setRemoving(undefined)
            }}
          />
        )}
      </Dialog>
    </Card>
  )
}

function RenameDialog({
  attachment,
  onClose,
  onSave,
}: {
  attachment: Attachment
  onClose: () => void
  onSave: (name: string) => Promise<void>
}) {
  const [name, setName] = useState(attachment.name)
  const save = useMutation({ mutationFn: onSave })
  const inputId = useId()

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    if (name.trim() === '') return
    save.mutate(name.trim())
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{t('docPanels.attachments.renameTitle')}</DialogTitle>
            <DialogDescription>{attachment.name}</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <label htmlFor={inputId} className="text-sm font-medium">
              {t('docPanels.attachments.newName')}
            </label>
            <Input
              id={inputId}
              value={name}
              maxLength={255}
              onChange={(event) => {
                setName(event.target.value)
              }}
            />
          </div>
          {save.error && <InlineError error={save.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={save.isPending || name.trim() === ''}>
              {t('common.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function RemoveDialogContent({
  attachment,
  onRemove,
  onCancel,
}: {
  attachment: Attachment
  onRemove: () => Promise<void>
  onCancel: () => void
}) {
  const remove = useMutation({ mutationFn: onRemove })
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>
          {t('docPanels.attachments.removeTitle', { name: attachment.name })}
        </DialogTitle>
        <DialogDescription>{t('docPanels.attachments.removeText')}</DialogDescription>
      </DialogHeader>
      {remove.error && <InlineError error={remove.error} />}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button
          type="button"
          variant="destructive"
          disabled={remove.isPending}
          onClick={() => {
            remove.mutate()
          }}
        >
          {t('docPanels.attachments.remove')}
        </Button>
      </DialogFooter>
    </DialogContent>
  )
}
