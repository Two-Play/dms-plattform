import { createFileRoute } from '@tanstack/react-router'
import { CompliancePage } from '@/components/compliance/compliance-page'
import { parseCompliancePageSearch } from '@/components/compliance/compliance-params'

export const Route = createFileRoute('/_app/compliance')({
  validateSearch: parseCompliancePageSearch,
  component: CompliancePage,
})
