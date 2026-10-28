import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { NewDocumentPage } from '@/components/documents/new-document-page'

export const Route = createFileRoute('/_app/documents/new')({
  component: NewDocumentRoute,
})

function NewDocumentRoute() {
  const navigate = useNavigate()
  return (
    <NewDocumentPage
      onSubmitted={(documentId) => {
        // After submission the document page shows its review state.
        void navigate({ to: '/documents/$documentId', params: { documentId } })
      }}
    />
  )
}
