import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

/** Stand-in for screens that later issues will build. */
export function PlaceholderPage({ title, description }: { title: string; description: string }) {
  return (
    <div className="space-y-6">
      <h1 className="font-heading text-2xl font-semibold">{title}</h1>
      <Card>
        <CardHeader>
          <CardTitle>Coming soon</CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
      </Card>
    </div>
  )
}
