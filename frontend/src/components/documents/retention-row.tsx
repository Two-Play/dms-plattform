import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { documentRetentionQuery, setDocumentRetention } from '@/api/retention'
import { InlineError } from '@/components/shared/error-state'
import { ruleText } from '@/components/retention/rule-text'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

const SOURCES = ['Class', 'RecordsManagement', 'Entered'] as const

const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

/**
 * The retention rule of a version in the metadata panel (DMS-133): name and period, where it comes
 * from, a hint on drafts whose class has none, and for records managers a choice of rule for the
 * document (its open draft and later versions).
 */
export function RetentionRow({
  documentId,
  version,
}: {
  documentId: string
  version: DocumentVersionView
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const retention = useQuery(documentRetentionQuery(client, documentId, version.id))
  const [choosing, setChoosing] = useState(false)
  const [choice, setChoice] = useState('')

  const mutation = useMutation({
    mutationFn: () => setDocumentRetention(client, documentId, choice || undefined),
    onSuccess: async () => {
      toast(t('docs.meta.retentionSaved'))
      setChoosing(false)
      await queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
    },
  })

  const data = retention.data
  // A draft saved before its class got a rule takes the class's rule over on its next save or submission.
  const pending = !data?.rule && version.status === 'Draft' ? data?.classRule : undefined
  const rule = data?.rule ?? pending
  const source = pending ? 'Class' : SOURCES.find((s) => s === data?.source)

  return (
    <div className="contents">
      <dt className="text-muted-foreground">{t('docs.meta.retention')}</dt>
      <dd className="min-w-0 space-y-1 break-words" data-testid="retention">
        {retention.isPending ? (
          <span className="text-muted-foreground">{t('docs.meta.retentionLoading')}</span>
        ) : retention.isError ? (
          <InlineError error={retention.error} />
        ) : rule ? (
          <>
            <span>{ruleText(rule)}</span>
            {source && (
              <span className="block text-xs text-muted-foreground">
                {t(`docs.meta.retentionFrom.${source}`)}
              </span>
            )}
          </>
        ) : version.status === 'Draft' ? (
          <span role="note" className="block text-amber-700 dark:text-amber-400">
            {t('docs.meta.retentionNone')}
          </span>
        ) : (
          <span className="text-muted-foreground">{t('docs.meta.retentionNoneReleased')}</span>
        )}
        {data?.documentRule && data.documentRule.key !== rule?.key && (
          <span className="block text-xs text-muted-foreground">
            {t('docs.meta.retentionDocumentRule', { rule: ruleText(data.documentRule) })}
          </span>
        )}
        {data?.canChange && !choosing && (
          <Button
            variant="ghost"
            size="sm"
            className="h-auto px-0"
            aria-label={t('docs.meta.retentionChangeLabel')}
            onClick={() => {
              setChoice(data.documentRule?.key ?? '')
              setChoosing(true)
            }}
          >
            {t('docs.meta.retentionChange')}
          </Button>
        )}
        {data?.canChange && choosing && (
          <form
            className="grid gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              mutation.mutate()
            }}
          >
            <label htmlFor={`${id}-rule`} className="sr-only">
              {t('docs.meta.retentionSelect')}
            </label>
            <select
              id={`${id}-rule`}
              className={selectClass}
              value={choice}
              onChange={(e) => {
                setChoice(e.target.value)
              }}
            >
              <option value="">
                {data.classRule
                  ? t('docs.meta.retentionClassOption', { rule: ruleText(data.classRule) })
                  : t('docs.meta.retentionClassNone')}
              </option>
              {(data.rules ?? []).map((r) => (
                <option key={r.key} value={r.key}>
                  {ruleText(r)}
                </option>
              ))}
            </select>
            {mutation.isError && <InlineError error={mutation.error} />}
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={mutation.isPending}>
                {mutation.isPending ? t('common.loading') : t('docs.meta.retentionSave')}
              </Button>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => {
                  setChoosing(false)
                }}
              >
                {t('docs.meta.retentionCancel')}
              </Button>
            </div>
          </form>
        )}
      </dd>
    </div>
  )
}
