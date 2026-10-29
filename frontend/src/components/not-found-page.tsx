import { Link } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { FullPageMessage } from './full-page-message'

export function NotFoundPage() {
  return (
    <FullPageMessage title={t('notFound.title')}>
      <p className="text-muted-foreground">{t('notFound.text')}</p>
      <Button asChild>
        <Link to="/">{t('notFound.home')}</Link>
      </Button>
    </FullPageMessage>
  )
}
