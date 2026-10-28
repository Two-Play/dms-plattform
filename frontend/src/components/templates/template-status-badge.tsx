import { statusLabel } from '@/components/documents/status-label'
import { Badge } from '@/components/ui/badge'

const variants: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  Draft: 'outline',
  Testing: 'secondary',
  Released: 'default',
  Deprecated: 'destructive',
  Archived: 'outline',
}

/** Status of a template version. */
export function TemplateStatusBadge({ status }: { status: string }) {
  return <Badge variant={variants[status] ?? 'outline'}>{statusLabel(status)}</Badge>
}
