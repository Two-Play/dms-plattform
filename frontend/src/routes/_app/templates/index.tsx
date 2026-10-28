import { createFileRoute } from '@tanstack/react-router'
import { TemplateListPage } from '@/components/templates/template-list-page'

export const Route = createFileRoute('/_app/templates/')({
  component: TemplateListPage,
})
