import { Link } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { FullPageMessage } from './full-page-message'

export function NotFoundPage() {
  return (
    <FullPageMessage title="Page not found">
      <p className="text-muted-foreground">The page you are looking for does not exist.</p>
      <Button asChild>
        <Link to="/">Go to start page</Link>
      </Button>
    </FullPageMessage>
  )
}
