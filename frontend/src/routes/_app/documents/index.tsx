import { createFileRoute, Link } from '@tanstack/react-router'
import { FilePlus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

export const Route = createFileRoute('/_app/documents/')({
  component: DocumentsPage,
})

function DocumentsPage() {
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="font-heading text-2xl font-semibold">Documents</h1>
        <Button asChild>
          <Link to="/documents/new">
            <FilePlus />
            New document
          </Link>
        </Button>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Coming soon</CardTitle>
          <CardDescription>Search, view and manage documents.</CardDescription>
        </CardHeader>
      </Card>
    </div>
  )
}
