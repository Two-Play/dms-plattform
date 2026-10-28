import { createFileRoute } from '@tanstack/react-router'
import { parseCompareSearch } from '@/components/documents/compare-params'
import { VersionComparePage } from '@/components/documents/version-compare-page'

// Not nested in the document page (`_`): the comparison is a page of its own.
export const Route = createFileRoute('/_app/documents/$documentId_/compare')({
  validateSearch: parseCompareSearch,
  component: CompareRoute,
})

function CompareRoute() {
  const { documentId } = Route.useParams()
  const { from, to } = Route.useSearch()
  return (
    <VersionComparePage
      key={`${from ?? ''}-${to ?? ''}`}
      documentId={documentId}
      from={from}
      to={to}
    />
  )
}
