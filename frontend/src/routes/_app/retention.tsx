import { createFileRoute } from '@tanstack/react-router'
import { RetentionPage } from '@/components/retention/retention-page'
import { parseRetentionPageSearch } from '@/components/retention/retention-params'

export const Route = createFileRoute('/_app/retention')({
  validateSearch: parseRetentionPageSearch,
  component: RetentionPage,
})
