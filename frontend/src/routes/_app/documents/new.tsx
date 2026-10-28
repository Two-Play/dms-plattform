import { createFileRoute } from '@tanstack/react-router'
import { NewDocumentPage } from '@/components/documents/new-document-page'

export const Route = createFileRoute('/_app/documents/new')({
  component: NewDocumentPage,
})
