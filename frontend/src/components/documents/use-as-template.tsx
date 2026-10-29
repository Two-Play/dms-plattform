import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LayoutTemplate } from 'lucide-react'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import { templateFromVersion } from '@/api/uploads'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

/**
 * "Use as template" (DMS-127): a template designer makes a new Word template draft from the Word file of a
 * version, with its field definitions and without the values entered for this document.
 */
export function UseAsTemplate({
  documentId,
  versionId,
  fileName,
}: {
  documentId: string
  versionId: string
  fileName: string
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const nameId = useId()
  const descriptionId = useId()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState(fileName.replace(/\.[^.]+$/, ''))
  const [description, setDescription] = useState('')
  const create = useMutation({
    mutationFn: () =>
      templateFromVersion(client, documentId, versionId, {
        name: name.trim(),
        description: description.trim() || undefined,
      }),
    onSuccess: async (created) => {
      toast(t('upload.fromVersion.created', { name: created.templateName }))
      setOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
      await queryClient.invalidateQueries({ queryKey: ['word-templates'] })
    },
  })

  if (!open) {
    return (
      <div className="space-y-1">
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            create.reset()
            setOpen(true)
          }}
        >
          <LayoutTemplate className="size-4" aria-hidden />
          {t('upload.fromVersion.action')}
        </Button>
        {create.data && (
          <p className="text-xs" role="status">
            <a
              className="underline underline-offset-4"
              href={`/templates/${create.data.templateId}`}
            >
              {t('upload.fromVersion.open')}
            </a>
          </p>
        )}
      </div>
    )
  }

  return (
    <form
      className="grid w-full gap-2 rounded-md border p-3 lg:max-w-xl"
      aria-labelledby={`${nameId}-title`}
      onSubmit={(event) => {
        event.preventDefault()
        create.mutate()
      }}
    >
      <p id={`${nameId}-title`} className="font-medium">
        {t('upload.fromVersion.title')}
      </p>
      <p className="text-xs text-muted-foreground">{t('upload.fromVersion.text')}</p>
      <div className="space-y-1.5">
        <Label htmlFor={nameId}>{t('upload.fromVersion.name')}</Label>
        <Input
          id={nameId}
          required
          value={name}
          onChange={(event) => {
            setName(event.target.value)
          }}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={descriptionId}>{t('upload.fromVersion.description')}</Label>
        <Input
          id={descriptionId}
          value={description}
          onChange={(event) => {
            setDescription(event.target.value)
          }}
        />
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="sm" disabled={create.isPending || name.trim() === ''}>
          {create.isPending ? t('upload.fromVersion.creating') : t('upload.fromVersion.create')}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => {
            setOpen(false)
          }}
        >
          {t('upload.fromVersion.cancel')}
        </Button>
      </div>
      {create.error && (
        <p role="alert" className="text-sm text-destructive">
          {t('upload.fromVersion.failed', { message: create.error.message })}
        </p>
      )}
    </form>
  )
}
