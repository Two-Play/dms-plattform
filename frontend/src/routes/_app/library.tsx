import { createFileRoute } from '@tanstack/react-router'
import { LibraryPage } from '@/components/library/library-page'
import { parseLibraryPageSearch } from '@/components/library/library-params'

export const Route = createFileRoute('/_app/library')({
  validateSearch: parseLibraryPageSearch,
  component: LibraryPage,
})
