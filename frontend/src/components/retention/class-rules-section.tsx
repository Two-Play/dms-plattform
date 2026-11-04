import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive } from 'lucide-react'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  classRetentionQuery,
  classTree,
  retentionRulesQuery,
  saveClassRetention,
  type ClassRetention,
  type RetentionRule,
} from '@/api/retention'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'
import { ruleText } from './rule-text'

const th = 'py-2 pr-4 font-medium'
const td = 'py-2 pr-4 align-top'
const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

function summaryOf(rules: readonly RetentionRule[], key: string): string {
  const rule = rules.find((r) => r.key === key)
  return ruleText(
    rule
      ? {
          key,
          name: rule.name,
          periodMonths: rule.periodMonths,
          trigger: rule.trigger,
          fromYearEnd: rule.fromYearEnd,
        }
      : { key },
  )
}

function ClassRow({
  cls,
  depth,
  rules,
  labels,
}: {
  cls: ClassRetention
  depth: number
  rules: readonly RetentionRule[]
  labels: Map<string, string>
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const [choice, setChoice] = useState(cls.ruleKey ?? '')
  const changed = choice !== (cls.ruleKey ?? '')
  const mutation = useMutation({
    mutationFn: () => saveClassRetention(client, cls.classKey, choice || undefined),
    onSuccess: async () => {
      toast(t('retention.classRules.done', { class: cls.label }))
      await queryClient.invalidateQueries({ queryKey: ['retention', 'class-rules'] })
    },
  })
  const inheritedLabel = cls.inheritedFrom
    ? (labels.get(cls.inheritedFrom) ?? cls.inheritedFrom)
    : undefined

  return (
    <tr className="border-b last:border-0" data-testid={`class-rule-${cls.classKey}`}>
      <td className={td} style={{ paddingLeft: `${depth * 1.25}rem` }}>
        <span>{cls.label}</span>{' '}
        <span className="font-mono text-xs text-muted-foreground">{cls.classKey}</span>
      </td>
      <td className={td}>
        <select
          className={selectClass}
          aria-label={t('retention.classRules.ruleLabel', { class: cls.label })}
          value={choice}
          onChange={(e) => {
            setChoice(e.target.value)
          }}
        >
          <option value="">{t('retention.classRules.inherit')}</option>
          {rules.map((r) => (
            <option key={r.key} value={r.key}>
              {summaryOf(rules, r.key)}
            </option>
          ))}
        </select>
        {mutation.isError && <InlineError error={mutation.error} />}
      </td>
      <td className={td}>
        {cls.effectiveRule ? (
          <>
            <span>{summaryOf(rules, cls.effectiveRule)}</span>
            {inheritedLabel && (
              <span className="block text-xs text-muted-foreground">
                {t('retention.classRules.inheritedFrom', { class: inheritedLabel })}
              </span>
            )}
          </>
        ) : (
          <>
            <span className="text-amber-700 dark:text-amber-400">
              {t('retention.classRules.none')}
            </span>
            <span className="block text-xs text-muted-foreground">
              {t('retention.classRules.noneHint')}
            </span>
          </>
        )}
      </td>
      <td className="py-2 align-top">
        <Button
          size="sm"
          variant={changed ? 'default' : 'ghost'}
          disabled={!changed || mutation.isPending}
          aria-label={t('retention.classRules.saveLabel', { class: cls.label })}
          onClick={() => {
            mutation.mutate()
          }}
        >
          {mutation.isPending ? t('common.loading') : t('retention.classRules.save')}
        </Button>
      </td>
    </tr>
  )
}

/**
 * The retention rule of every document class (DMS-133): records managers assign a rule to a class;
 * its documents get it, subclasses without their own rule inherit it.
 */
export function ClassRulesSection() {
  const client = useApiClient()
  const classes = useQuery(classRetentionQuery(client))
  const rules = useQuery(retentionRulesQuery(client))
  const labels = new Map((classes.data ?? []).map((c) => [c.classKey, c.label]))

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{t('retention.classRules.caption')}</h2>
        </CardTitle>
        <CardDescription>{t('retention.classRules.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {classes.isPending || rules.isPending ? (
          <LoadingState label={t('retention.classRules.loading')} />
        ) : classes.isError ? (
          <ErrorState error={classes.error} onRetry={() => void classes.refetch()} />
        ) : rules.isError ? (
          <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />
        ) : classes.data.length === 0 ? (
          <EmptyState icon={Archive} title={t('retention.classRules.empty')} />
        ) : (
          <div className="space-y-2">
            {rules.data.length === 0 && (
              <p className="text-sm text-muted-foreground">{t('retention.classRules.noRules')}</p>
            )}
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <caption className="sr-only">{t('retention.classRules.caption')}</caption>
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th scope="col" className={th}>
                      {t('retention.classRules.colClass')}
                    </th>
                    <th scope="col" className={th}>
                      {t('retention.classRules.colRule')}
                    </th>
                    <th scope="col" className={th}>
                      {t('retention.classRules.colEffective')}
                    </th>
                    <th scope="col" className={th}>
                      <span className="sr-only">{t('retention.classRules.save')}</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {classTree(classes.data).map(({ cls, depth }) => (
                    <ClassRow
                      // A saved assignment resets the row's choice.
                      key={`${cls.classId}-${cls.ruleKey ?? ''}`}
                      cls={cls}
                      depth={depth}
                      rules={rules.data}
                      labels={labels}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
