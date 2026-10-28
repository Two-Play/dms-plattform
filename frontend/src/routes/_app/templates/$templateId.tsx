import { createFileRoute } from '@tanstack/react-router'
import { parseTemplateSearch } from '@/components/templates/template-params'
import { TemplatePage } from '@/components/templates/template-page'

export const Route = createFileRoute('/_app/templates/$templateId')({
  validateSearch: parseTemplateSearch,
  component: TemplateRoute,
})

function TemplateRoute() {
  const { templateId } = Route.useParams()
  const { version } = Route.useSearch()
  return <TemplatePage key={templateId} templateId={templateId} version={version} />
}
