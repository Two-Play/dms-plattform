import { createFileRoute } from '@tanstack/react-router'
import { DocumentListPage } from '@/components/documents/document-list-page'

export const Route = createFileRoute('/_app/documents/')({
  component: DocumentListPage,
})
