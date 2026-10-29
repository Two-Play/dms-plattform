import { useMutation } from '@tanstack/react-query'
import { CheckCircle2, XCircle } from 'lucide-react'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import { runTest, type TemplateVersion, type TestRun } from '@/api/template-admin'
import { DRAFT_LABEL } from '@/components/documents/document-preview'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { errorMessage, locationLabel } from './template-lifecycle'
// The document stylesheet shared with the PDF rendering (modules/templates, DMS-39).
import '@dms-document/document.css'

function outcomes(values: boolean[] | undefined): string {
  const v = values ?? []
  if (v.length === 0) return 'not reached'
  return v.map((b) => (b ? 'true' : 'false')).join(' and ')
}

function Results({ run }: { run: TestRun }) {
  const cases = run.cases ?? []
  const [selected, setSelected] = useState(0)
  const current = cases[selected]
  return (
    <div className="space-y-4">
      <p className="flex items-center gap-2 text-sm" role="status">
        {run.allBranchesCovered ? (
          <CheckCircle2 className="size-4 text-primary" aria-hidden="true" />
        ) : (
          <XCircle className="size-4 text-destructive" aria-hidden="true" />
        )}
        {run.allBranchesCovered
          ? `All condition branches covered by ${String(cases.length)} test cases.`
          : `Not every condition branch is covered by the ${String(cases.length)} test cases.`}
      </p>
      {(run.coverage ?? []).length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">Branch coverage</caption>
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-1 pr-3 font-medium">
                  Condition
                </th>
                <th scope="col" className="py-1 pr-3 font-medium">
                  Where
                </th>
                <th scope="col" className="py-1 font-medium">
                  Reached as
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
      <div className="grid gap-4 lg:grid-cols-[minmax(0,16rem)_minmax(0,1fr)]">
        <ul aria-label="Test cases" className="space-y-1">
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
                  <span className="text-xs text-destructive">Failed</span>
                ) : (c.missingRequired ?? []).length > 0 ? (
                  <span className="text-xs text-muted-foreground">
                    {(c.missingRequired ?? []).length} required values missing
                  </span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
        {current && (
          <section aria-label={`Test case ${current.name}`} className="min-w-0 space-y-2">
            <div className="flex items-center justify-between gap-2">
              <h3 className="font-medium">{current.name}</h3>
              <Badge variant="destructive">{DRAFT_LABEL}</Badge>
            </div>
            {current.error && (
              <p role="alert" className="text-sm text-destructive">
                {current.error}
              </p>
            )}
            {(current.missingRequired ?? []).length > 0 && (
              <p className="text-xs text-muted-foreground">
                Missing required values: {(current.missingRequired ?? []).join(', ')}
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
export function TestModePanel({ version }: { version: TemplateVersion }) {
  const client = useApiClient()
  const id = useId()
  const [language, setLanguage] = useState<'en' | 'de'>('en')
  const test = useMutation({
    mutationFn: () => runTest(client, version.templateId, version.version, language),
  })

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1.5">
          <Label htmlFor={`${id}-language`}>Language</Label>
          <select
            id={`${id}-language`}
            value={language}
            onChange={(e) => {
              setLanguage(e.target.value === 'de' ? 'de' : 'en')
            }}
            className="h-8 rounded-md border bg-background px-2 text-sm"
          >
            <option value="en">English</option>
            <option value="de">German</option>
          </select>
        </div>
        <Button
          onClick={() => {
            test.mutate()
          }}
          disabled={test.isPending}
        >
          {test.isPending ? 'Running…' : 'Run test mode'}
        </Button>
      </div>
      {test.isError && (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(test.error)}
        </p>
      )}
      {test.data && <Results key={test.submittedAt} run={test.data} />}
    </div>
  )
}
