import { Hash } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  assignClassNumberRange,
  changeNumberRange,
  classNumberRangesQuery,
  createNumberRange,
  numberRangesQuery,
  previewNumberRange,
  type AssignmentMode,
  type ClassNumberRange,
  type NumberRange,
} from '@/api/number-ranges'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'
import { useDebouncedValue } from '@/lib/use-debounced-value'

/** Range keys as the backend accepts them (`NumberRange.KeyPattern`). */
export const rangeKeyPattern = /^[a-z0-9][a-z0-9-]{0,62}$/

const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

interface RangeInput {
  key: string
  name: string
  pattern: string
  startValue: string
  yearlyReset: boolean
}

const emptyInput: RangeInput = {
  key: '',
  name: '',
  pattern: '',
  startValue: '1',
  yearlyReset: false,
}

function inputOf(range: NumberRange): RangeInput {
  return {
    key: range.key,
    name: range.name,
    pattern: range.pattern,
    startValue: String(range.startValue),
    yearlyReset: range.yearlyReset,
  }
}

function startOf(input: RangeInput): number | undefined {
  return /^\d{1,12}$/.test(input.startValue.trim()) ? Number(input.startValue.trim()) : undefined
}

/** The next number for the settings being typed, checked by the backend after a short pause. */
function PatternPreview({ input, id }: { input: RangeInput; id: string }) {
  const client = useApiClient()
  const start = startOf(input) ?? 0
  const settings = useDebouncedValue(
    { pattern: input.pattern.trim(), start, yearly: input.yearlyReset },
    300,
  )
  const preview = useQuery({
    queryKey: ['number-ranges', 'preview', settings],
    queryFn: () => previewNumberRange(client, settings.pattern, settings.start, settings.yearly),
    enabled: settings.pattern !== '',
    staleTime: 60_000,
  })
  if (settings.pattern === '') return null
  return (
    <div id={id} aria-live="polite" className="text-sm" data-testid="number-preview">
      {preview.isPending && <p className="text-muted-foreground">{t('numberRanges.checking')}</p>}
      {preview.isError && <InlineError error={preview.error} />}
      {preview.data?.valid && preview.data.example && (
        <p>
          <span className="font-medium">
            {t('numberRanges.preview', { number: preview.data.example })}
          </span>
          {preview.data.example.includes('{OU}') && (
            <span className="block text-xs text-muted-foreground">
              {t('numberRanges.previewOu', { unit: '{OU}' })}
            </span>
          )}
        </p>
      )}
      {preview.data && !preview.data.valid && (
        <ul role="alert" className="list-disc pl-5 text-destructive">
          {(preview.data.problems ?? []).map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Create a range, or edit one (`range` given; the key stays). */
function RangeForm({ range, onDone }: { range?: NumberRange; onDone?: () => void }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [input, setInput] = useState<RangeInput>(range ? inputOf(range) : emptyInput)
  const [attempted, setAttempted] = useState(false)
  const start = startOf(input)
  const problems = {
    key: range || rangeKeyPattern.test(input.key.trim()) ? undefined : t('numberRanges.keyProblem'),
    name: input.name.trim() === '' ? t('numberRanges.nameMissing') : undefined,
    start: start === undefined ? t('numberRanges.startProblem') : undefined,
  }
  const save = useMutation({
    mutationFn: () =>
      range
        ? changeNumberRange(client, range.id, {
            name: input.name.trim(),
            pattern: input.pattern.trim(),
            startValue: start ?? 0,
            yearlyReset: input.yearlyReset,
          })
        : createNumberRange(client, {
            key: input.key.trim(),
            name: input.name.trim(),
            pattern: input.pattern.trim(),
            startValue: start ?? 0,
            yearlyReset: input.yearlyReset,
          }),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: ['number-ranges'] })
      toast(
        range
          ? t('numberRanges.saved', { name: saved.name })
          : t('numberRanges.created', { name: saved.name }),
      )
      if (!range) {
        setInput(emptyInput)
        setAttempted(false)
      }
      onDone?.()
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (problems.key ?? problems.name ?? problems.start) return
    save.mutate()
  }

  function field<K extends keyof RangeInput>(k: K, value: RangeInput[K]) {
    setInput((current) => ({ ...current, [k]: value }))
  }

  const shown = attempted ? problems : { key: undefined, name: undefined, start: undefined }
  return (
    <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2" noValidate>
      {!range && (
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-key`}>{t('numberRanges.key')}</Label>
          <Input
            id={`${id}-key`}
            value={input.key}
            placeholder="contracts"
            onChange={(e) => {
              field('key', e.target.value)
            }}
            aria-required
            aria-invalid={shown.key ? true : undefined}
            aria-describedby={`${id}-key-hint`}
          />
          <p
            id={`${id}-key-hint`}
            role={shown.key ? 'alert' : undefined}
            className={shown.key ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
          >
            {shown.key ?? t('numberRanges.keyHint')}
          </p>
        </div>
      )}
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-name`}>{t('numberRanges.name')}</Label>
        <Input
          id={`${id}-name`}
          value={input.name}
          onChange={(e) => {
            field('name', e.target.value)
          }}
          aria-required
          aria-invalid={shown.name ? true : undefined}
        />
        {shown.name && (
          <p role="alert" className="text-sm text-destructive">
            {shown.name}
          </p>
        )}
      </div>
      <div className="grid gap-1.5 sm:col-span-2">
        <Label htmlFor={`${id}-pattern`}>{t('numberRanges.pattern')}</Label>
        <Input
          id={`${id}-pattern`}
          value={input.pattern}
          placeholder="VTR-{YYYY}-{NNNN}"
          className="font-mono"
          onChange={(e) => {
            field('pattern', e.target.value)
          }}
          aria-required
          aria-describedby={`${id}-pattern-hint ${id}-preview`}
        />
        <p id={`${id}-pattern-hint`} className="text-xs text-muted-foreground">
          {t('numberRanges.patternHint', {
            year: '{YYYY} / {YY}',
            unit: '{OU}',
            counter: '{NNNN}',
          })}
        </p>
        <PatternPreview input={input} id={`${id}-preview`} />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-start`}>{t('numberRanges.startValue')}</Label>
        <Input
          id={`${id}-start`}
          inputMode="numeric"
          value={input.startValue}
          onChange={(e) => {
            field('startValue', e.target.value)
          }}
          aria-invalid={shown.start ? true : undefined}
        />
        {shown.start && (
          <p role="alert" className="text-sm text-destructive">
            {shown.start}
          </p>
        )}
      </div>
      <div className="flex items-start gap-2 sm:pt-7">
        <input
          id={`${id}-yearly`}
          type="checkbox"
          className="mt-1 size-4"
          checked={input.yearlyReset}
          onChange={(e) => {
            field('yearlyReset', e.target.checked)
          }}
          aria-describedby={`${id}-yearly-hint`}
        />
        <div>
          <Label htmlFor={`${id}-yearly`}>{t('numberRanges.yearlyReset')}</Label>
          <p id={`${id}-yearly-hint`} className="text-xs text-muted-foreground">
            {t('numberRanges.yearlyResetHint')}
          </p>
        </div>
      </div>
      {range && (
        <p className="text-sm text-muted-foreground sm:col-span-2">
          {t('numberRanges.futureOnly')}
        </p>
      )}
      <div className="flex gap-2 sm:col-span-2">
        <Button type="submit" disabled={save.isPending}>
          {range
            ? save.isPending
              ? t('numberRanges.saving')
              : t('numberRanges.save')
            : save.isPending
              ? t('numberRanges.creating')
              : t('numberRanges.create')}
        </Button>
        {range && onDone && (
          <Button type="button" variant="outline" onClick={onDone}>
            {t('numberRanges.cancel')}
          </Button>
        )}
      </div>
      {save.isError && <InlineError error={save.error} className="sm:col-span-2" />}
    </form>
  )
}

function RangeTable({
  ranges,
  classes,
  onEdit,
}: {
  ranges: NumberRange[]
  classes: ClassNumberRange[]
  onEdit: (range: NumberRange) => void
}) {
  const labels = new Map(classes.map((c) => [c.classId, c.label]))
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{t('numberRanges.rangesCaption')}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('numberRanges.colName')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('numberRanges.colPattern')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('numberRanges.colNext')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('numberRanges.colReset')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('numberRanges.colClasses')}
            </th>
            <th scope="col" className="py-2 font-medium">
              <span className="sr-only">{t('numberRanges.colActions')}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {ranges.map((r) => (
            <tr key={r.id} className="border-b align-top last:border-0">
              <td className="py-2 pr-4">
                {r.name}
                <span className="block text-xs text-muted-foreground">{r.key}</span>
              </td>
              <td className="py-2 pr-4 font-mono">{r.pattern}</td>
              <td className="py-2 pr-4 font-mono" data-testid={`next-${r.key}`}>
                {r.nextNumber}
              </td>
              <td className="py-2 pr-4">
                {r.yearlyReset ? t('numberRanges.yes') : t('numberRanges.no')}
              </td>
              <td className="py-2 pr-4">
                {(r.classIds ?? []).map((c) => labels.get(c) ?? c).join(', ')}
              </td>
              <td className="py-2">
                <Button
                  size="sm"
                  variant="outline"
                  aria-label={t('numberRanges.editLabel', { name: r.name })}
                  onClick={() => {
                    onEdit(r)
                  }}
                >
                  {t('numberRanges.edit')}
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function modeOf(c: ClassNumberRange): string {
  if (c.mode === 'range' && c.numberRangeId) return c.numberRangeId
  return c.mode === 'none' ? 'none' : 'inherit'
}

function ClassAssignments({
  classes,
  ranges,
}: {
  classes: ClassNumberRange[]
  ranges: NumberRange[]
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const names = new Map(ranges.map((r) => [r.id, r.name]))
  const labels = new Map(classes.map((c) => [c.classId, c.label]))
  const assign = useMutation({
    mutationFn: ({ cls, value }: { cls: ClassNumberRange; value: string }) => {
      const mode: AssignmentMode =
        value === 'inherit' ? 'inherit' : value === 'none' ? 'none' : 'range'
      return assignClassNumberRange(client, cls.classId, mode, mode === 'range' ? value : undefined)
    },
    onSuccess: async (updated) => {
      toast(t('numberRanges.assigned', { cls: updated.label }))
      await queryClient.invalidateQueries({ queryKey: ['number-ranges'] })
    },
  })
  if (classes.length === 0) {
    return <p className="text-sm text-muted-foreground">{t('numberRanges.noClasses')}</p>
  }
  return (
    <div className="space-y-2">
      {assign.isError && <InlineError error={assign.error} />}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">{t('numberRanges.classesCaption')}</caption>
          <thead className="text-left text-xs text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('numberRanges.colClass')}
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('numberRanges.colAssignment')}
              </th>
              <th scope="col" className="py-2 font-medium">
                {t('numberRanges.colEffective')}
              </th>
            </tr>
          </thead>
          <tbody>
            {classes.map((c) => {
              const effective = c.effectiveRangeId
                ? (names.get(c.effectiveRangeId) ?? c.effectiveRangeId)
                : undefined
              return (
                <tr key={c.classId} className="border-b align-top last:border-0">
                  <td className="py-2 pr-4">
                    {c.label}
                    <span className="block text-xs text-muted-foreground">{c.classKey}</span>
                  </td>
                  <td className="py-2 pr-4">
                    <select
                      className={selectClass}
                      aria-label={t('numberRanges.assignmentLabel', { cls: c.label })}
                      value={modeOf(c)}
                      disabled={assign.isPending}
                      onChange={(e) => {
                        assign.mutate({ cls: c, value: e.target.value })
                      }}
                    >
                      <option value="inherit">{t('numberRanges.modeInherit')}</option>
                      <option value="none">{t('numberRanges.modeNone')}</option>
                      {ranges.map((r) => (
                        <option key={r.id} value={r.id}>
                          {t('numberRanges.rangeOption', { name: r.name })}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="py-2" data-testid={`effective-${c.classKey}`}>
                    {effective === undefined
                      ? t('numberRanges.noEffective')
                      : c.inheritedFrom
                        ? t('numberRanges.inherited', {
                            range: effective,
                            cls: labels.get(c.inheritedFrom) ?? c.inheritedFrom,
                          })
                        : effective}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/**
 * Number ranges (DMS-113): create and edit ranges with a preview of the next number, and assign
 * them to document classes. Changes only affect numbers assigned afterwards.
 */
export function NumberRangesPanel() {
  const client = useApiClient()
  const ranges = useQuery(numberRangesQuery(client))
  const classes = useQuery(classNumberRangesQuery(client))
  const [editing, setEditing] = useState<NumberRange>()

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>{t('numberRanges.newRange')}</CardTitle>
          <CardDescription>{t('numberRanges.newRangeText')}</CardDescription>
        </CardHeader>
        <CardContent>
          <RangeForm />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>
            <h2>{t('numberRanges.ranges')}</h2>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {ranges.isPending && <LoadingState label={t('numberRanges.loading')} rows={3} />}
          {ranges.isError && (
            <ErrorState error={ranges.error} onRetry={() => void ranges.refetch()} />
          )}
          {ranges.data && ranges.data.length === 0 && (
            <EmptyState
              icon={Hash}
              title={t('numberRanges.none')}
              description={t('numberRanges.noneText')}
            />
          )}
          {ranges.data && ranges.data.length > 0 && (
            <RangeTable ranges={ranges.data} classes={classes.data ?? []} onEdit={setEditing} />
          )}
          {editing && (
            <section aria-label={t('numberRanges.editTitle', { name: editing.name })}>
              <h3 className="mb-3 font-medium">
                {t('numberRanges.editTitle', { name: editing.name })}
              </h3>
              <RangeForm
                key={editing.id}
                range={editing}
                onDone={() => {
                  setEditing(undefined)
                }}
              />
            </section>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>
            <h2>{t('numberRanges.classes')}</h2>
          </CardTitle>
          <CardDescription>{t('numberRanges.classesText')}</CardDescription>
        </CardHeader>
        <CardContent>
          {classes.isPending && <LoadingState label={t('numberRanges.loadingClasses')} />}
          {classes.isError && (
            <ErrorState error={classes.error} onRetry={() => void classes.refetch()} />
          )}
          {classes.data && <ClassAssignments classes={classes.data} ranges={ranges.data ?? []} />}
        </CardContent>
      </Card>
    </div>
  )
}
