import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import {
  checkContent,
  requestOf,
  saveDraft,
  type ContentIssue,
  type TemplateContent,
  type TemplateVersion,
} from '@/api/template-admin'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { contentText, errorMessage, locationLabel } from './template-lifecycle'

type Parsed = { ok: true; content: TemplateContent } | { ok: false; message: string }

function parse(text: string): Parsed {
  try {
    const value: unknown = JSON.parse(text)
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      return { ok: false, message: 'The content must be a JSON object with "sections".' }
    }
    return { ok: true, content: value }
  } catch (e) {
    return { ok: false, message: `Not valid JSON: ${e instanceof Error ? e.message : String(e)}` }
  }
}

type Outcome =
  | { kind: 'saved' }
  | { kind: 'valid' }
  | { kind: 'issues'; issues: ContentIssue[] }
  | { kind: 'error'; message: string }

/**
 * JSON editor for the content of a draft version (DMS-98). "Check" asks the server to compile the content
 * against the class profile; "Save" stores it. Compiler issues are listed with their location (section,
 * block path, part, line and column). A structured editor follows later.
 */
export function ContentEditor({ version }: { version: TemplateVersion }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [text, setText] = useState(() => contentText(version.content))
  // The text last loaded or saved: the server may normalize the content it returns, so compare with this.
  const [saved, setSaved] = useState(text)
  const [outcome, setOutcome] = useState<Outcome | undefined>()
  const changed = text !== saved

  const check = (content: TemplateContent) =>
    checkContent(client, version.documentClassId, version.documentClassVersion, content)

  const checkMutation = useMutation({
    mutationFn: check,
    onSuccess: (result) => {
      setOutcome(result.valid ? { kind: 'valid' } : { kind: 'issues', issues: result.issues ?? [] })
    },
    onError: (e) => {
      setOutcome({ kind: 'error', message: errorMessage(e) })
    },
  })

  const save = useMutation({
    mutationFn: (content: TemplateContent) =>
      saveDraft(client, version.templateId, version.version, requestOf(version, content)),
    onSuccess: async (_data, content) => {
      setSaved(contentText(content))
      setText(contentText(content))
      setOutcome({ kind: 'saved' })
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
    },
    onError: async (e, content) => {
      // The save answer describes the issues as one text; the check endpoint gives them with locations.
      if (e instanceof ApiRequestError && e.code === 'invalid_content') {
        try {
          const result = await check(content)
          if (!result.valid) {
            setOutcome({ kind: 'issues', issues: result.issues ?? [] })
            return
          }
        } catch {
          // fall through to the save error
        }
      }
      setOutcome({ kind: 'error', message: errorMessage(e) })
    },
  })

  function run(action: (content: TemplateContent) => void) {
    const parsed = parse(text)
    if (!parsed.ok) {
      setOutcome({ kind: 'error', message: parsed.message })
      return
    }
    setOutcome(undefined)
    action(parsed.content)
  }

  const busy = checkMutation.isPending || save.isPending

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label htmlFor={`${id}-content`}>Content (JSON)</Label>
        <Textarea
          id={`${id}-content`}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
          }}
          spellCheck={false}
          aria-describedby={outcome ? `${id}-outcome` : undefined}
          className="min-h-80 font-mono text-xs"
        />
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          disabled={busy}
          onClick={() => {
            run((c) => {
              checkMutation.mutate(c)
            })
          }}
        >
          Check
        </Button>
        <Button
          disabled={busy || !changed}
          onClick={() => {
            run((c) => {
              save.mutate(c)
            })
          }}
        >
          {save.isPending ? 'Saving…' : 'Save draft'}
        </Button>
        {changed && (
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() => {
              setText(saved)
              setOutcome(undefined)
            }}
          >
            Discard changes
          </Button>
        )}
      </div>
      <div id={`${id}-outcome`}>
        {outcome?.kind === 'saved' && (
          <p role="status" className="text-sm text-muted-foreground">
            Draft saved.
          </p>
        )}
        {outcome?.kind === 'valid' && (
          <p role="status" className="text-sm text-muted-foreground">
            No issues: the content compiles against the document class.
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
              {outcome.issues.length === 1
                ? '1 issue in the content'
                : `${String(outcome.issues.length)} issues in the content`}
            </p>
            <ul className="mt-1 list-disc space-y-1 pl-5">
              {outcome.issues.map((issue, i) => (
                <li key={i}>
                  <span className="font-mono text-xs">{locationLabel(issue.location)}</span>:{' '}
                  {issue.message}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  )
}
