import { Badge } from '@/components/ui/badge'
import { statusLabel } from './status-label'

const variants: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  Draft: 'outline',
  InReview: 'secondary',
  InApproval: 'secondary',
  Running: 'secondary',
  Returned: 'destructive',
  Rendering: 'secondary',
  Approved: 'default',
  Published: 'default',
}

/** Status of a document version or workflow case. */
export function StatusBadge({ status }: { status: string }) {
  return <Badge variant={variants[status] ?? 'outline'}>{statusLabel(status)}</Badge>
}
