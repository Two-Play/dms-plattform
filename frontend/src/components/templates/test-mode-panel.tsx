import { useMutation } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, XCircle } from 'lucide-react'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  runTest,
  type ComputedHint,
  type TemplateVersion,
  type TestConditionHint,
  type TestRun,
} from '@/api/template-admin'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { getLocale, t } from '@/i18n'
import { errorMessage, locationLabel } from './template-lifecycle'
// The document stylesheet shared with the PDF rendering (modules/templates, DMS-39).
import '@dms-document/document.css'

function outcomes(values: boolean[] | undefined): string {
  const v = values ?? []
  if (v.length === 0) return t('templates.test.notReached')
  return v
    .map((b) => (b ? t('templates.test.true') : t('templates.test.false')))
    .join(t('templates.test.and'))
}

function labels(fields: { label: string }[] | undefined): string {
  return (fields ?? []).map((f) => f.label).join(t('templates.test.and'))
}

/** The test cases that showed a hint; choosing one shows it rendered. */
function SeenIn({ names, onSelect }: { names: string[]; onSelect: (name: string) => void }) {
  return (
    <p className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
      {t('templates.test.hints.seenIn')}
      {names.map((name) => (
        <button
          key={name}
          type="button"
          className="underline underline-offset-2 hover:text-foreground"
          onClick={() => {
            onSelect(name)
          }}
        >
          {name}
        </button>
      ))}
    </p>
  )
}

function ComputedHintItem({
  hint,
  onSelect,
}: {
  hint: ComputedHint
  onSelect: (name: string) => void
}) {
  return (
    <li className="space-y-1 rounded-md border p-2">
      <p>
        {t('templates.test.hints.computed', {
          name: hint.label,
          fields: labels(hint.missingInputs),
        })}
      </p>
      <p className="text-xs text-muted-foreground">
        {hint.suggestion ? t('templates.test.hints.suggestion') : t('templates.test.hints.guard')}
      </p>
      <code className="block overflow-x-auto rounded bg-muted px-2 py-1 font-mono text-xs">
        {hint.suggestion ?? hint.guard}
      </code>
      {hint.suggestion && (
        <p className="text-xs text-muted-foreground">{t('templates.test.hints.fallback')}</p>
      )}
      <SeenIn names={hint.cases ?? []} onSelect={onSelect} />
    </li>
  )
}

function ConditionHintItem({
  hint,
  onSelect,
}: {
  hint: TestConditionHint
  onSelect: (name: string) => void
}) {
  return (
    <li className="space-y-1 rounded-md border p-2">
      <code className="block overflow-x-auto font-mono text-xs">{hint.condition}</code>
      <p className="text-xs text-muted-foreground">{locationLabel(hint.location)}</p>
      <p>{t('templates.test.hints.condition', { fields: labels(hint.missingInputs) })}</p>
      <SeenIn names={hint.cases ?? []} onSelect={onSelect} />
    </li>
  )
}

/** What the run found about empty fields (ADR 0065): the designer can still change the template here. */
function Hints({ run, onSelect }: { run: TestRun; onSelect: (name: string) => void }) {
  const computed = run.computedHints ?? []
  const conditions = run.conditionHints ?? []
  if (computed.length === 0 && conditions.length === 0) return null
  return (
    <section aria-label={t('templates.test.hints.title')} className="space-y-3 text-sm">
      {computed.length > 0 && (
        <div className="space-y-1">
          <h3 className="flex items-center gap-2 font-medium">
            <AlertTriangle className="size-4 text-destructive" aria-hidden="true" />
            {t('templates.test.hints.computedTitle')}
          </h3>
          <ul className="space-y-2">
            {computed.map((h) => (
              <ComputedHintItem key={h.key} hint={h} onSelect={onSelect} />
            ))}
          </ul>
        </div>
      )}
      {conditions.length > 0 && (
        <div className="space-y-1">
          <h3 className="font-medium">{t('templates.test.hints.conditionsTitle')}</h3>
          <p className="text-xs text-muted-foreground">
            {t('templates.test.hints.conditionGuard')}
          </p>
          <ul className="space-y-2">
            {conditions.map((h) => (
              <ConditionHintItem key={locationLabel(h.location)} hint={h} onSelect={onSelect} />
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function Results({ run }: { run: TestRun }) {
  const cases = run.cases ?? []
  const [selected, setSelected] = useState(0)
  const current = cases[selected]
  const select = (name: string) => {
    const index = cases.findIndex((c) => c.name === name)
    if (index >= 0) setSelected(index)
  }
  return (
    <div className="space-y-4">
      <p className="flex items-center gap-2 text-sm" role="status">
        {run.allBranchesCovered ? (
          <CheckCircle2 className="size-4 text-primary" aria-hidden="true" />
        ) : (
          <XCircle className="size-4 text-destructive" aria-hidden="true" />
        )}
        {run.allBranchesCovered
          ? t('templates.test.covered', { count: cases.length })
          : t('templates.test.notCovered', { count: cases.length })}
      </p>
      {(run.coverage ?? []).length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">{t('templates.test.coverage')}</caption>
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-1 pr-3 font-medium">
                  {t('templates.test.condition')}
                </th>
                <th scope="col" className="py-1 pr-3 font-medium">
                  {t('templates.test.where')}
                </th>
                <th scope="col" className="py-1 font-medium">
                  {t('templates.test.reachedAs')}
                </th>
              </tr>
            </thead>
            <tbody>
              {(run.coverage ?? []).map((b) => (
                <tr key={locationLabel(b.location)} className="border-b last:border-0">
                  <td className="py-1 pr-3 font-mono text-xs">{b.condition}</td>
                  <td className="py-1 pr-3 text-xs text-muted-foreground">
                    {locationLabel(b.location)}
                  </td>
                  <td className="py-1 text-xs">{outcomes(b.outcomes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Hints run={run} onSelect={select} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,16rem)_minmax(0,1fr)]">
        <ul aria-label={t('templates.test.cases')} className="space-y-1">
          {cases.map((c, i) => (
            <li key={c.name}>
              <button
                type="button"
                aria-pressed={i === selected}
                onClick={() => {
                  setSelected(i)
                }}
                className="w-full rounded-md border px-2 py-1.5 text-left text-sm hover:bg-muted aria-pressed:border-primary aria-pressed:bg-muted"
              >
                <span className="block">{c.name}</span>
                {c.error ? (
                  <span className="text-xs text-destructive">{t('templates.test.failed')}</span>
                ) : (c.missingRequired ?? []).length > 0 ? (
                  <span className="text-xs text-muted-foreground">
                    {t('templates.test.missingCount', { count: (c.missingRequired ?? []).length })}
                  </span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
        {current && (
          <section
            aria-label={t('templates.test.caseLabel', { name: current.name })}
            className="min-w-0 space-y-2"
          >
            <div className="flex items-center justify-between gap-2">
              <h3 className="font-medium">{current.name}</h3>
              <Badge variant="destructive">{t('docs.preview.draftLabel')}</Badge>
            </div>
            {current.error && (
              <p role="alert" className="text-sm text-destructive">
                {current.error}
              </p>
            )}
            {(current.missingRequired ?? []).length > 0 && (
              <p className="text-xs text-muted-foreground">
                {t('templates.test.missing', { list: (current.missingRequired ?? []).join(', ') })}
              </p>
            )}
            {current.html && (
              // Rendered by the backend's HtmlRenderer, which escapes all text (as in the live preview).
              <div
                className="@container overflow-x-clip rounded-lg bg-muted p-2 md:p-4"
                data-testid="test-case-preview"
                dangerouslySetInnerHTML={{ __html: current.html }}
              />
            )}
          </section>
        )}
      </div>
    </div>
  )
}

/**
 * Test mode (DMS-35): resolves the version with generated sample data that reaches every condition branch,
 * leaves optional fields empty and uses long values, and shows each case rendered like the live preview.
 */
export function TestModePanel({
  version,
  unsaved = false,
}: {
  version: TemplateVersion
  /** The editor holds changes: a run would test the stored version, not what is on the screen. */
  unsaved?: boolean
}) {
  const client = useApiClient()
  const id = useId()
  const [language, setLanguage] = useState<'en' | 'de'>(getLocale)
  const test = useMutation({
    mutationFn: () => runTest(client, version.templateId, version.version, language),
  })

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1.5">
          <Label htmlFor={`${id}-language`}>{t('templates.test.language')}</Label>
          <select
            id={`${id}-language`}
            value={language}
            onChange={(e) => {
              setLanguage(e.target.value === 'de' ? 'de' : 'en')
            }}
            className="h-8 rounded-md border bg-background px-2 text-sm"
          >
            <option value="en">{t('templates.test.english')}</option>
            <option value="de">{t('templates.test.german')}</option>
          </select>
        </div>
        <Button
          onClick={() => {
            test.mutate()
          }}
          disabled={test.isPending || unsaved}
          aria-describedby={unsaved ? `${id}-save-first` : undefined}
        >
          {test.isPending ? t('templates.test.running') : t('templates.test.run')}
        </Button>
      </div>
      {unsaved && (
        <p id={`${id}-save-first`} className="text-xs text-muted-foreground">
          {t('templates.page.saveFirst')}
        </p>
      )}
      {test.isError && (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(test.error)}
        </p>
      )}
      {test.data && <Results key={test.submittedAt} run={test.data} />}
    </div>
  )
}
