import { createFileRoute } from '@tanstack/react-router'
import { DocumentPage } from '@/components/documents/document-page'

export const Route = createFileRoute('/_app/documents/$documentId')({
  component: DocumentRoute,
})

function DocumentRoute() {
  const { documentId } = Route.useParams()
  // Keyed by id: switching documents starts with a fresh page.
  return <DocumentPage key={documentId} documentId={documentId} />
}
