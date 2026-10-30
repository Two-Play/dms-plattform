import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { UploadWizard } from '@/components/documents/upload-wizard'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
} from '@/components/shared/page-header'
import { t } from '@/i18n'

export const Route = createFileRoute('/_app/documents/upload')({
  component: UploadRoute,
})

/** "Upload document" (DMS-127): the wizard; afterwards the new document or template page. */
function UploadRoute() {
  const navigate = useNavigate()
  return (
    <UploadWizard
      breadcrumbs={
        <Breadcrumbs>
          <Link to="/documents" className={breadcrumbLinkClass}>
            {t('docs.page.breadcrumb')}
          </Link>
          <BreadcrumbCurrent>{t('upload.title')}</BreadcrumbCurrent>
        </Breadcrumbs>
      }
      onDocumentCreated={(documentId) => {
        void navigate({ to: '/documents/$documentId', params: { documentId } })
      }}
      onTemplateCreated={(templateId) => {
        void navigate({ to: '/templates/$templateId', params: { templateId } })
      }}
    />
  )
}
