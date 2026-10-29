import { createFileRoute } from '@tanstack/react-router'
import { MyDeputiesPage } from '@/components/deputies/my-deputies-page'

export const Route = createFileRoute('/_app/deputies')({
  component: MyDeputiesPage,
})
