import { createFileRoute } from '@tanstack/react-router'
import { parseDocumentSearch } from '@/components/documents/compare-params'
import { DocumentPage } from '@/components/documents/document-page'

export const Route = createFileRoute('/_app/documents/$documentId')({
  validateSearch: parseDocumentSearch,
  component: DocumentRoute,
})

function DocumentRoute() {
  const { documentId } = Route.useParams()
  const { version } = Route.useSearch()
  // Keyed by id: switching documents starts with a fresh page.
  return <DocumentPage key={documentId} documentId={documentId} versionId={version} />
}
