import { PageHeader } from '@/components/shared/page-header'
import { t } from '@/i18n'
import { PublicationFailuresPanel } from './publication-failures'
import { RenditionFailuresPanel } from './rendition-failures-panel'

/**
 * The failures a person can act on, wherever their role is restricted (DMS-146, ADR 0064): records
 * managers of a unit or class, to whom the legal holds page is closed, and the person named for a
 * document who may retry it without being able to open its version. The backend decides what is
 * listed, so there is nothing to refuse here: for anyone else the lists are empty.
 */
export function FailuresPage() {
  return (
    <div className="space-y-6">
      <PageHeader title={t('failures.title')} description={t('failures.description')} />
      <RenditionFailuresPanel />
      <PublicationFailuresPanel />
    </div>
  )
}
