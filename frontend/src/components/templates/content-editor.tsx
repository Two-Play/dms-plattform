import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useId, useMemo, useState } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import {
  checkContent,
  designerFieldsQuery,
  requestOf,
  saveDraft,
  type ContentIssue,
  type TemplateContent,
  type TemplateVersion,
} from '@/api/template-admin'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import {
  contentKey,
  fromEditor,
  parseContent,
  placeIssues,
  REFERENCES_TARGET,
  toEditor,
  UNPLACED_TARGET,
  type EditorContent,
} from './editor/content-model'
import {
  EditorEnvContext,
  fieldVariables,
  nodeId,
  ScopeContext,
  type EditorEnv,
} from './editor/editor-context'
import type { ScopeVariable } from './editor/expressions'
import { ComputedEditor, ReferencesEditor } from './editor/fields-editors'
import { SectionsEditor } from './editor/sections-editor'
import { UnsavedChangesGuard } from './editor/unsaved-changes'
import { contentText, errorMessage, locationLabel } from './template-lifecycle'

type Outcome =
  | { kind: 'saved' }
  | { kind: 'valid' }
  | { kind: 'issues'; issues: ContentIssue[]; placed: Map<string, ContentIssue[]>; checked: string }
  | { kind: 'error'; message: string }

function focusLater(elementId: string) {
  requestAnimationFrame(() => {
    document.getElementById(elementId)?.focus()
  })
}

function issueTarget(uid: string): string | undefined {
  if (uid === UNPLACED_TARGET) return undefined
  if (uid === REFERENCES_TARGET) return 'tpl-references'
  return nodeId(uid)
}

/** The optional JSON view of the content (hidden by default): read, or edit and apply. */
function AdvancedView({
  content,
  readOnly,
  draft,
  onDraft,
  onApply,
}: {
  content: EditorContent
  readOnly: boolean
  draft: string | undefined
  onDraft: (text: string | undefined) => void
  onApply: (content: TemplateContent) => void
}) {
  const id = useId()
  const [error, setError] = useState<string | undefined>()
  const text = draft ?? contentText(fromEditor(content))

  function apply() {
    if (draft === undefined) return
    const parsed = parseContent(draft)
    if (parsed.ok) {
      setError(undefined)
      onApply(parsed.content)
      return
    }
    setError(
      'syntax' in parsed
        ? t('templates.editor.invalidJson', { message: parsed.syntax })
        : t('templateEditor.advanced.shape', {
            path: parsed.path,
            expected: t(`templateEditor.advanced.expected.${parsed.problem}`),
          }),
    )
  }

  return (
    <div className="space-y-2">
      <Label htmlFor={`${id}-json`}>{t('templates.editor.label')}</Label>
      <p className="text-xs text-muted-foreground">{t('templateEditor.advanced.hint')}</p>
      <Textarea
        id={`${id}-json`}
        value={text}
        readOnly={readOnly}
        spellCheck={false}
        aria-describedby={error ? `${id}-error` : undefined}
        aria-invalid={error ? true : undefined}
        onChange={(e) => {
          onDraft(e.target.value)
        }}
        className="min-h-80 font-mono text-xs"
      />
      {error && (
        <p id={`${id}-error`} role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {!readOnly && (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" disabled={draft === undefined} onClick={apply}>
            {t('templateEditor.advanced.apply')}
          </Button>
          {draft !== undefined && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setError(undefined)
                onDraft(undefined)
              }}
            >
              {t('templateEditor.advanced.reset')}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Visual editor for the content of a structured template version (DMS-128, ADR 0051): sections, text
 * blocks with formatting and placeholders, lists, tables, clauses, conditions, repeats, computed fields
 * and reference fields, edited through forms. "Check" compiles the content against the class profile on
 * the server and shows each issue at its element; "Save" stores the draft. Leaving with unsaved changes
 * asks first. The JSON is an optional advanced view. Read-only for versions that are not drafts.
 */
export function ContentEditor({
  version,
  readOnly = false,
}: {
  version: TemplateVersion
  readOnly?: boolean
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [content, setContent] = useState(() => toEditor(version.content))
  // The content last loaded or saved, in the editor's canonical form.
  const [saved, setSaved] = useState(() => contentKey(content))
  const [outcome, setOutcome] = useState<Outcome | undefined>()
  const [advanced, setAdvanced] = useState(false)
  const [jsonDraft, setJsonDraft] = useState<string | undefined>()
  const [announcement, setAnnouncement] = useState('')
  const fields = useQuery(designerFieldsQuery(client, version.templateId, version.version))

  const key = contentKey(content)
  const dirty = !readOnly && (key !== saved || jsonDraft !== undefined)

  const fieldVars = useMemo(() => fieldVariables(fields.data), [fields.data])
  const scope: ScopeVariable[] = useMemo(
    () => [
      ...fieldVars,
      ...content.computed
        .filter((c) => c.key !== '')
        .map((c) => ({
          path: c.key,
          label: c.key,
          kind: 'Unknown' as const,
          multiple: false,
          source: 'computed' as const,
        })),
    ],
    [fieldVars, content.computed],
  )

  const focus = useCallback((elementId: string) => {
    focusLater(elementId)
  }, [])
  const announce = useCallback((message: string) => {
    setAnnouncement(message)
  }, [])
  const issues = outcome?.kind === 'issues' ? outcome.placed : undefined
  const env: EditorEnv = useMemo(
    () => ({ readOnly, issues: issues ?? new Map(), focus, announce }),
    [readOnly, issues, focus, announce],
  )

  const check = (snapshot: EditorContent) =>
    checkContent(
      client,
      version.documentClassId,
      version.documentClassVersion,
      fromEditor(snapshot),
    )

  const showIssues = (snapshot: EditorContent, list: ContentIssue[]) => {
    setOutcome({
      kind: 'issues',
      issues: list,
      placed: placeIssues(snapshot, list),
      checked: contentKey(snapshot),
    })
  }

  const checkMutation = useMutation({
    mutationFn: check,
    onSuccess: (result, snapshot) => {
      if (result.valid) setOutcome({ kind: 'valid' })
      else showIssues(snapshot, result.issues ?? [])
    },
    onError: (e) => {
      setOutcome({ kind: 'error', message: errorMessage(e) })
    },
  })

  const save = useMutation({
    mutationFn: (snapshot: EditorContent) =>
      saveDraft(
        client,
        version.templateId,
        version.version,
        requestOf(version, fromEditor(snapshot)),
      ),
    onSuccess: async (_data, snapshot) => {
      setSaved(contentKey(snapshot))
      setOutcome({ kind: 'saved' })
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
    },
    onError: async (e, snapshot) => {
      // The save answer describes the issues as one text; the check endpoint gives them with locations.
      if (e instanceof ApiRequestError && e.code === 'invalid_content') {
        try {
          const result = await check(snapshot)
          if (!result.valid) {
            showIssues(snapshot, result.issues ?? [])
            return
          }
        } catch {
          // fall through to the save error
        }
      }
      setOutcome({ kind: 'error', message: errorMessage(e) })
    },
  })

  const busy = checkMutation.isPending || save.isPending
  const update = (patch: Partial<EditorContent>) => {
    setContent((c) => ({ ...c, ...patch }))
  }

  return (
    <EditorEnvContext value={env}>
      <ScopeContext value={scope}>
        <div className="space-y-6">
          {fields.isError && !readOnly && (
            <p className="text-xs text-muted-foreground">{t('templateEditor.fieldsUnavailable')}</p>
          )}
          <section aria-labelledby={`${id}-sections`} className="space-y-3">
            <h3 id={`${id}-sections`} className="text-sm font-semibold">
              {t('templateEditor.sections')}
            </h3>
            <SectionsEditor
              sections={content.sections}
              onChange={(sections) => {
                update({ sections })
              }}
            />
          </section>
          <section aria-labelledby={`${id}-computed`} className="space-y-3">
            <div>
              <h3 id={`${id}-computed`} className="text-sm font-semibold">
                {t('templateEditor.computed.title')}
              </h3>
              <p className="text-xs text-muted-foreground">{t('templateEditor.computed.text')}</p>
            </div>
            <ComputedEditor
              computed={content.computed}
              fields={fieldVars}
              onChange={(computed) => {
                update({ computed })
              }}
            />
          </section>
          <section
            id="tpl-references"
            tabIndex={-1}
            aria-labelledby={`${id}-references`}
            className="space-y-3 outline-none"
          >
            <div>
              <h3 id={`${id}-references`} className="text-sm font-semibold">
                {t('templateEditor.references.title')}
              </h3>
              <p className="text-xs text-muted-foreground">{t('templateEditor.references.text')}</p>
            </div>
            <ReferencesEditor
              references={content.references}
              fields={fieldVars}
              onChange={(references) => {
                update({ references })
              }}
            />
          </section>

          {!readOnly && (
            <div className="sticky bottom-0 z-10 -mx-2 flex flex-wrap items-center gap-2 border-t bg-background/95 px-2 py-2 backdrop-blur">
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => {
                  setOutcome(undefined)
                  checkMutation.mutate(content)
                }}
              >
                {t('templates.editor.check')}
              </Button>
              <Button
                disabled={busy || key === saved || jsonDraft !== undefined}
                onClick={() => {
                  setOutcome(undefined)
                  save.mutate(content)
                }}
              >
                {save.isPending ? t('templates.editor.saving') : t('templates.editor.save')}
              </Button>
              {dirty && (
                <>
                  <Button
                    variant="ghost"
                    disabled={busy}
                    onClick={() => {
                      setContent(toEditor(JSON.parse(saved) as TemplateContent))
                      setJsonDraft(undefined)
                      setOutcome(undefined)
                    }}
                  >
                    {t('templates.editor.discard')}
                  </Button>
                  <span className="text-xs text-muted-foreground" role="status">
                    {jsonDraft !== undefined
                      ? t('templateEditor.jsonPending')
                      : t('templateEditor.unsavedChanges')}
                  </span>
                </>
              )}
            </div>
          )}

          <div id={`${id}-outcome`}>
            {outcome?.kind === 'saved' && (
              <p role="status" className="text-sm text-muted-foreground">
                {t('templates.editor.saved')}
              </p>
            )}
            {outcome?.kind === 'valid' && (
              <p role="status" className="text-sm text-muted-foreground">
                {t('templates.editor.valid')}
              </p>
            )}
            {outcome?.kind === 'error' && (
              <p role="alert" className="text-sm text-destructive">
                {outcome.message}
              </p>
            )}
            {outcome?.kind === 'issues' && (
              <div
                role="alert"
                className="rounded-lg border border-destructive/40 p-3 text-sm text-destructive"
              >
                <p className="font-medium">
                  {t('templates.editor.issues', { count: outcome.issues.length })}
                </p>
                <p className="text-xs">{t('templateEditor.issuesAtElements')}</p>
                {outcome.checked !== key && (
                  <p className="text-xs">{t('templateEditor.changedSinceCheck')}</p>
                )}
                <ul className="mt-1 list-disc space-y-1 pl-5">
                  {[...outcome.placed.entries()].flatMap(([uid, list]) =>
                    list.map((issue, i) => {
                      const target = issueTarget(uid)
                      return (
                        <li key={`${uid}-${String(i)}`}>
                          <span className="font-mono text-xs">{locationLabel(issue.location)}</span>
                          : {issue.message}
                          {target && (
                            <Button
                              size="xs"
                              variant="link"
                              className="h-auto px-1 text-destructive"
                              onClick={() => {
                                focusLater(target)
                              }}
                            >
                              {t('templateEditor.goTo')}
                            </Button>
                          )}
                        </li>
                      )
                    }),
                  )}
                </ul>
              </div>
            )}
          </div>

          <div className="space-y-2 border-t pt-3">
            <Button
              size="sm"
              variant="ghost"
              aria-expanded={advanced}
              aria-controls={`${id}-advanced`}
              onClick={() => {
                setAdvanced((a) => !a)
              }}
            >
              {advanced ? t('templateEditor.advanced.hide') : t('templateEditor.advanced.show')}
            </Button>
            <div id={`${id}-advanced`} hidden={!advanced}>
              {advanced && (
                <AdvancedView
                  content={content}
                  readOnly={readOnly}
                  draft={jsonDraft}
                  onDraft={setJsonDraft}
                  onApply={(c) => {
                    setContent(toEditor(c))
                    setJsonDraft(undefined)
                    setOutcome(undefined)
                  }}
                />
              )}
            </div>
          </div>
          <p aria-live="polite" className="sr-only">
            {announcement}
          </p>
          {!readOnly && <UnsavedChangesGuard dirty={dirty} />}
        </div>
      </ScopeContext>
    </EditorEnvContext>
  )
}
