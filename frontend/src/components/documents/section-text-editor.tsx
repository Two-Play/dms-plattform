import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { useApiClient } from '@/api/context'
import { standardTextQuery } from '@/api/deviations'
import { updateDraftContent } from '@/api/documents'
import { ApiRequestError } from '@/api/errors'
import type { DocumentVersionView } from '@/api/partners'
import { templateFieldsQuery } from '@/api/templates'
import { LoadingState } from '@/components/shared/states'
import { BlockList } from '@/components/templates/editor/block-editor'
import {
  computedName,
  fromEditor,
  toEditor,
  type EditorBlock,
  type Section,
} from '@/components/templates/editor/content-model'
import {
  EditorEnvContext,
  fieldVariables,
  ScopeContext,
  type EditorEnv,
} from '@/components/templates/editor/editor-context'
import type { ScopeVariable } from '@/components/templates/editor/expressions'
import { UnsavedChangesGuard } from '@/components/templates/editor/unsaved-changes'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

type StoredBlocks = NonNullable<Section['blocks']>

/** The section with this id; a section the content does not have counts as empty. */
function storedBlocks(version: DocumentVersionView, sectionId: string): StoredBlocks {
  return version.content.sections?.find((s) => s.id === sectionId)?.blocks ?? []
}

function toBlocks(blocks: StoredBlocks): EditorBlock[] {
  const sections = toEditor({ sections: [{ id: '', locked: true, blocks }] }).sections
  return sections[0]?.blocks ?? []
}

function fromBlocks(blocks: EditorBlock[]): StoredBlocks {
  const content = fromEditor({
    sections: [{ uid: '', id: '', locked: true, blocks }],
    computed: [],
    references: [],
    extra: {},
  })
  return content.sections?.[0]?.blocks ?? []
}

/** Blocks as a stable text in the editor's canonical form, to compare what was edited and what is stored. */
function blocksKey(blocks: EditorBlock[]): string {
  return JSON.stringify(fromBlocks(blocks))
}

function saveFailure(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t('deviations.edit.forbidden')
    if (error.status === 409) return t('deviations.edit.conflict', { message: error.message })
    if (error.status === 422) return t('deviations.edit.invalid', { message: error.message })
  }
  return t('deviations.edit.failed', { message: error.message })
}

export interface SectionTextEditorProps {
  documentId: string
  /** The latest known state of the draft; it follows saves and refreshes while the editor is open. */
  version: DocumentVersionView
  sectionId: string
  title: string
  /** Why the text cannot be saved right now: the text stays, saving does not. */
  blocked?: 'requestGone' | 'noLongerDraft'
  onClose: () => void
  /** Whether the editor holds text that is not saved. */
  onDirtyChange: (dirty: boolean) => void
}

/**
 * The text of a locked section with an active deviation request (DMS-154, ADR 0071), in the block editor of
 * templates (ADR 0051). Only the blocks of this section change: title, position and lock are not offered, and
 * the rest of the content is sent as it was loaded. The server decides with the edit rules of the template;
 * `expectedWorkingState` keeps a save from overwriting what was saved since.
 */
export function SectionTextEditor({
  documentId,
  version,
  sectionId,
  title,
  blocked,
  onClose,
  onDirtyChange,
}: SectionTextEditorProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const stored = storedBlocks(version, sectionId)
  const storedKey = blocksKey(toBlocks(stored))
  const [blocks, setBlocks] = useState(() => toBlocks(stored))
  // The stored text the editing started from: what a change elsewhere is told from.
  const [base, setBase] = useState(storedKey)
  const [announcement, setAnnouncement] = useState('')
  const heading = useRef<HTMLHeadingElement>(null)

  const fields = useQuery(templateFieldsQuery(client, version.templateId, version.templateVersion))
  const standard = useQuery(standardTextQuery(client, documentId, version.id))
  const standardBlocks = standard.data?.sections?.find((s) => s.id === sectionId)?.blocks

  const key = blocksKey(blocks)
  const dirty = key !== base
  const changedElsewhere = dirty && storedKey !== base && key !== storedKey

  // Nothing typed yet, or the store caught up with this text: the editor simply follows it.
  if (storedKey !== base && !changedElsewhere) {
    if (!dirty) setBlocks(toBlocks(stored))
    setBase(storedKey)
  }

  useEffect(() => {
    onDirtyChange(dirty)
  }, [dirty, onDirtyChange])
  useEffect(
    () => () => {
      onDirtyChange(false)
    },
    [onDirtyChange],
  )
  useEffect(() => {
    heading.current?.focus()
  }, [])
  // Submitted with nothing left to save: there is no reason to keep the editor open.
  useEffect(() => {
    if (blocked === 'noLongerDraft' && !dirty) onClose()
  }, [blocked, dirty, onClose])

  const save = useMutation({
    mutationFn: () =>
      updateDraftContent(client, documentId, {
        content: {
          ...version.content,
          sections: (version.content.sections ?? []).map((s) =>
            s.id === sectionId ? { ...s, blocks: fromBlocks(blocks) } : s,
          ),
        },
        expectedWorkingState: version.workingState,
      }),
    onSuccess: async () => {
      toast(t('deviations.edit.saved', { section: title }))
      // The panel, the report, the preview and the next working state come from the server.
      await queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
      onClose()
    },
    onError: (error) => {
      // What was shown is out of date, or the version left Draft: the page catches up, the text stays.
      if (error instanceof ApiRequestError && (error.status === 409 || error.status === 403)) {
        void queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
      }
    },
  })

  const scope: ScopeVariable[] = useMemo(
    () => [
      ...fieldVariables(fields.data),
      ...(version.content.computed ?? []).map((c) => ({
        path: c.key,
        label: computedName(c),
        kind: 'Unknown' as const,
        multiple: false,
        source: 'computed' as const,
      })),
    ],
    [fields.data, version.content.computed],
  )

  const focus = useCallback((elementId: string) => {
    requestAnimationFrame(() => {
      document.getElementById(elementId)?.focus()
    })
  }, [])
  const env: EditorEnv = useMemo(
    () => ({ readOnly: false, issues: new Map(), focus, announce: setAnnouncement }),
    [focus],
  )

  const reset = () => {
    setBlocks(toBlocks(stored))
    setBase(storedKey)
    save.reset()
  }
  const headingId = `${id}-heading`

  return (
    <EditorEnvContext value={env}>
      <ScopeContext value={scope}>
        <section aria-labelledby={headingId} className="mt-2 space-y-3 border-t pt-3">
          <div className="space-y-1">
            <h4
              id={headingId}
              ref={heading}
              tabIndex={-1}
              className="text-sm font-semibold outline-none"
            >
              {t('deviations.edit.title', { section: title })}
            </h4>
            <p className="text-xs text-muted-foreground">{t('deviations.edit.hint')}</p>
          </div>
          {fields.isPending ? (
            <LoadingState label={t('deviations.edit.loading')} rows={3} />
          ) : (
            <>
              {fields.isError && (
                <p className="text-xs text-muted-foreground">
                  {t('templateEditor.fieldsUnavailable')}
                </p>
              )}
              <BlockList
                blocks={blocks}
                onChange={setBlocks}
                label={t('deviations.edit.blocks', { section: title })}
              />
            </>
          )}
          {blocked && (
            <p role="alert" className="text-sm text-destructive">
              {t(`deviations.edit.${blocked}`)}
            </p>
          )}
          {changedElsewhere && (
            <div role="alert" className="space-y-2 rounded-lg border border-destructive/40 p-3">
              <p className="text-sm text-destructive">
                {t('deviations.edit.changedElsewhere.message')}
              </p>
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setBase(storedKey)
                  }}
                >
                  {t('deviations.edit.changedElsewhere.keep')}
                </Button>
                <Button size="sm" variant="outline" onClick={reset}>
                  {t('deviations.edit.changedElsewhere.load')}
                </Button>
              </div>
            </div>
          )}
          {save.isError && !changedElsewhere && (
            <p role="alert" className="text-sm text-destructive">
              {saveFailure(save.error)}
            </p>
          )}
          {standard.isError && (
            <p className="text-xs text-muted-foreground">
              {t('deviations.edit.restoreUnavailable', { message: standard.error.message })}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button
              size="sm"
              disabled={!dirty || changedElsewhere || blocked !== undefined || save.isPending}
              onClick={() => {
                save.mutate()
              }}
            >
              {save.isPending ? t('deviations.edit.saving') : t('deviations.edit.save')}
            </Button>
            {dirty ? (
              <Button size="sm" variant="ghost" disabled={save.isPending} onClick={reset}>
                {t('deviations.edit.discard')}
              </Button>
            ) : (
              <Button size="sm" variant="ghost" onClick={onClose}>
                {t('deviations.edit.close')}
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              disabled={standardBlocks === undefined || save.isPending}
              onClick={() => {
                if (standardBlocks === undefined) return
                setBlocks(toBlocks(standardBlocks))
                setAnnouncement(t('deviations.edit.restored'))
              }}
            >
              {t('deviations.edit.restore')}
            </Button>
            {dirty && (
              <span className="text-xs text-muted-foreground" role="status">
                {t('deviations.edit.unsaved')}
              </span>
            )}
          </div>
          <p aria-live="polite" className="sr-only">
            {announcement}
          </p>
          <UnsavedChangesGuard dirty={dirty} />
        </section>
      </ScopeContext>
    </EditorEnvContext>
  )
}
