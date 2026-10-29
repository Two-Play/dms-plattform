import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { NewDocumentPage } from '@/components/documents/new-document-page'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
} from '@/components/shared/page-header'

export const Route = createFileRoute('/_app/documents/new')({
  component: NewDocumentRoute,
})

function NewDocumentRoute() {
  const navigate = useNavigate()
  return (
    <NewDocumentPage
      breadcrumbs={
        <Breadcrumbs>
          <Link to="/documents" className={breadcrumbLinkClass}>
            Documents
          </Link>
          <BreadcrumbCurrent>New document</BreadcrumbCurrent>
        </Breadcrumbs>
      }
      onSubmitted={(documentId) => {
        // After submission the document page shows its review state.
        void navigate({ to: '/documents/$documentId', params: { documentId } })
      }}
    />
  )
}
