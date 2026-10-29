import { createFileRoute } from '@tanstack/react-router'
import { LegalHoldsPage } from '@/components/legal-holds/legal-holds-page'
import { parseLegalHoldsPageSearch } from '@/components/legal-holds/legal-holds-params'

export const Route = createFileRoute('/_app/legal-holds')({
  validateSearch: parseLegalHoldsPageSearch,
  component: LegalHoldsPage,
})
