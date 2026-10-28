import { createFileRoute } from '@tanstack/react-router'
import { AdminPage } from '@/components/admin/admin-page'

export const Route = createFileRoute('/_app/admin')({
  component: AdminPage,
})
