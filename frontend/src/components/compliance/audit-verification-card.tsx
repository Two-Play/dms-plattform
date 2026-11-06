import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, ShieldAlert } from 'lucide-react'
import { auditVerificationQuery, type AuditVerification } from '@/api/compliance'
import { useApiClient } from '@/api/context'
import { errorKind } from '@/components/shared/error-messages'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { formatDateTime, formatNumber } from '@/i18n/format'

type ViolationKey =
  | 'MissingEntry'
  | 'BrokenLink'
  | 'HashMismatch'
  | 'UnsupportedFormat'
  | 'AnchorMismatch'
  | 'CheckpointMismatch'
  | 'other'

function violationKey(kind: string | undefined): ViolationKey {
  switch (kind) {
    case 'MissingEntry':
    case 'BrokenLink':
    case 'HashMismatch':
    case 'UnsupportedFormat':
    case 'AnchorMismatch':
    case 'CheckpointMismatch':
      return kind
    default:
      return 'other'
  }
}

const warning = 'text-amber-700 dark:text-amber-400'

function Run({ run, lastRunAt }: { run: AuditVerification; lastRunAt: string }) {
  const violated = run.result === 'violated'
  const Icon = violated ? ShieldAlert : run.stale ? AlertTriangle : CheckCircle2
  return (
    <div className="space-y-1 text-sm">
      <p
        className={`flex items-center gap-2 font-medium ${violated ? 'text-destructive' : run.stale ? warning : ''}`}
      >
        <Icon className="size-4 shrink-0" aria-hidden />
        {violated ? t('compliance.verification.violated') : t('compliance.verification.intact')}
      </p>
      <p className="text-muted-foreground">
        {t('compliance.verification.lastRun', { date: formatDateTime(lastRunAt) })}
        {run.entriesChecked !== undefined &&
          ` · ${t('compliance.verification.entries', {
            count: run.entriesChecked,
            entries: formatNumber(run.entriesChecked),
          })}`}
      </p>
      {violated && (
        <p>
          {t(`compliance.verification.kinds.${violationKey(run.violationKind)}`)}
          {run.violationPosition !== undefined &&
            ` ${t('compliance.verification.position', {
              position: formatNumber(run.violationPosition),
            })}`}
          {run.violatedSince &&
            ` ${t('compliance.verification.since', { date: formatDateTime(run.violatedSince) })}`}
        </p>
      )}
      {violated && (
        <p className="text-muted-foreground">{t('compliance.verification.violatedHint')}</p>
      )}
      {run.stale && (
        <p className={warning}>
          {t('compliance.verification.stale', { days: run.staleAfterDays })}
        </p>
      )}
      {run.checkpointsCompared === false && (
        <p className={warning}>{t('compliance.verification.noCheckpoints')}</p>
      )}
    </div>
  )
}

/**
 * Whether the regular verification of the audit chain ran and what it found (ADR 0080). It stands
 * on its own, so that a finding stays visible when the figures cannot be loaded.
 */
export function AuditVerificationCard() {
  const client = useApiClient()
  const verification = useQuery(auditVerificationQuery(client))
  const violated = verification.data?.result === 'violated'
  // Without the role the figures below already say so, once is enough.
  if (verification.isError && errorKind(verification.error) === 'forbidden') return null
  return (
    <Card className={violated ? 'border-destructive/40' : undefined}>
      <CardHeader>
        <CardTitle>
          <h2>{t('compliance.verification.title')}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent>
        {verification.isPending ? (
          <LoadingState label={t('compliance.verification.loading')} />
        ) : verification.isError ? (
          <ErrorState error={verification.error} onRetry={() => void verification.refetch()} />
        ) : verification.data.lastRunAt === undefined ? (
          <p className={`flex items-center gap-2 text-sm ${warning}`}>
            <AlertTriangle className="size-4 shrink-0" aria-hidden />
            {t('compliance.verification.neverRan')}
          </p>
        ) : (
          <Run run={verification.data} lastRunAt={verification.data.lastRunAt} />
        )}
      </CardContent>
    </Card>
  )
}
