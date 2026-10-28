import { createFileRoute } from '@tanstack/react-router'
import { SearchPage } from '@/components/search/search-page'
import { parseSearchPageSearch } from '@/components/search/search-params'

export const Route = createFileRoute('/_app/search')({
  validateSearch: parseSearchPageSearch,
  component: SearchPage,
})
