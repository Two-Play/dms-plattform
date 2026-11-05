import { createFileRoute } from '@tanstack/react-router'
import { FailuresPage } from '@/components/documents/failures-page'

export const Route = createFileRoute('/_app/failures')({
  component: FailuresPage,
})
