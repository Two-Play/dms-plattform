import { useMutation, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { CheckCircle2, Download } from 'lucide-react'
import { useState } from 'react'
import { downloadComplianceList } from '@/api/compliance'
import { useApiClient } from '@/api/context'
import {
  fourEyesFindingsQuery,
  type FindingKind,
  type FourEyesFinding,
} from '@/api/four-eyes-report'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { formatDate } from '@/i18n/format'

const th = 'py-2 pr-4 font-medium'
const td = 'py-2 pr-4'

/** What was decided, and for a vote whether the review of the version still runs. */
function decision(f: FourEyesFinding): string {
  const kind = t(`compliance.fourEyes.kinds.${f.kind as FindingKind}`)
  return f.caseOpen ? `${kind} (${t('compliance.fourEyes.stillOpen')})` : kind
}

function Findings({ findings }: { findings: FourEyesFinding[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{t('compliance.fourEyes.title')}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            <th scope="col" className={th}>
              {t('compliance.document')}
            </th>
            <th scope="col" className={th}>
              {t('compliance.version')}
            </th>
            <th scope="col" className={th}>
              {t('compliance.fourEyes.decision')}
            </th>
            <th scope="col" className={th}>
              {t('compliance.fourEyes.person')}
            </th>
            <th scope="col" className={th}>
              {t('compliance.fourEyes.decidedOn')}
            </th>
            <th scope="col" className={th}>
              {t('compliance.fourEyes.changedOn')}
            </th>
          </tr>
        </thead>
        <tbody>
          {findings.map((f) => (
            <tr
              key={`${f.versionId}-${f.kind}-${f.decidedBy}-${f.editor}-${f.step ?? ''}-${f.round ?? 0}`}
              className="border-b last:border-0"
            >
              <td className={td}>
                <Link
                  to="/documents/$documentId"
                  params={{ documentId: f.documentId }}
                  search={{ version: f.versionId }}
                  className="font-medium underline-offset-4 hover:underline"
                >
                  {f.title}
                </Link>
              </td>
              <td className={`${td} font-mono text-xs`}>{f.versionLabel}</td>
              <td className={td}>{decision(f)}</td>
              <td className={td}>
                <UserName id={f.editor} />
                {f.editor !== f.decidedBy && (
                  <>
                    {' '}
                    ({t('compliance.fourEyes.deputy')} <UserName id={f.decidedBy} />)
                  </>
                )}
              </td>
              <td className={td}>{formatDate(f.decidedAt)}</td>
              <td className={td}>{formatDate(f.firstChangedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Downloads the findings as the backend builds them, which records the export (ADR 0077, 0078). */
function ExportFindings() {
  const client = useApiClient()
  const download = useMutation({
    mutationFn: () =>
      downloadComplianceList(client, 'four-eyes-findings', {
        unit: undefined,
        owner: undefined,
        document: undefined,
      }),
  })
  return (
    <div className="flex flex-col items-end gap-1">
      <Button
        size="sm"
        variant="outline"
        disabled={download.isPending}
        aria-label={t('compliance.exportLabel', { list: t('compliance.fourEyes.title') })}
        onClick={() => {
          download.mutate()
        }}
      >
        <Download className="size-4" aria-hidden />
        {download.isPending ? t('compliance.exporting') : t('compliance.export')}
      </Button>
      {download.isError && <InlineError error={download.error} />}
    </div>
  )
}

/**
 * The retroactive check of the four-eyes rule in the cockpit (business concept 2.4, DMS-163, ADR
 * 0078). Reading the report names people and is audited, so it loads on request and not with the
 * cockpit.
 */
export function FourEyesFindings() {
  const client = useApiClient()
  const [opened, setOpened] = useState(false)
  const list = useQuery({ ...fourEyesFindingsQuery(client), enabled: opened })
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('compliance.fourEyes.title')}</CardTitle>
        <CardDescription>{t('compliance.fourEyes.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {!opened ? (
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setOpened(true)
            }}
          >
            {t('compliance.fourEyes.show')}
          </Button>
        ) : list.isPending ? (
          <LoadingState label={t('compliance.loadingList')} />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.length === 0 ? (
          <EmptyState
            icon={CheckCircle2}
            title={t('compliance.fourEyes.none')}
            description={t('compliance.fourEyes.noneText')}
          />
        ) : (
          <div className="space-y-3">
            <div className="flex justify-end">
              <ExportFindings />
            </div>
            <Findings findings={list.data} />
          </div>
        )}
      </CardContent>
    </Card>
  )
}
