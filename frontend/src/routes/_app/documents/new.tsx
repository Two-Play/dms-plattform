import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { NewDocumentPage } from '@/components/documents/new-document-page'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
} from '@/components/shared/page-header'
import { t } from '@/i18n'

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
            {t('docs.page.breadcrumb')}
          </Link>
          <BreadcrumbCurrent>{t('docs.new.title')}</BreadcrumbCurrent>
        </Breadcrumbs>
      }
      onCreated={(documentId) => {
        // File and Word documents continue on their page: upload, fields, preview (DMS-115).
        void navigate({ to: '/documents/$documentId', params: { documentId } })
      }}
      onSubmitted={(documentId) => {
        // After submission the document page shows its review state.
        void navigate({ to: '/documents/$documentId', params: { documentId } })
      }}
    />
  )
}
