import { useQuery } from '@tanstack/react-query'
import { openAcknowledgementsQuery } from '@/api/acknowledgements'
import { useApiClient } from '@/api/context'
import { openReviewTasksQuery } from '@/api/reviews'
import { myTasksQuery } from '@/api/workflow'

function count(data: unknown): number {
  return Array.isArray(data) ? data.length : 0
}

/**
 * Number of open tasks in the inbox: review and approval tasks, periodic reviews and read
 * confirmations. `undefined` until the workflow tasks are loaded; sources that fail count as zero.
 */
export function useOpenTaskCount(): number | undefined {
  const client = useApiClient()
  const tasks = useQuery(myTasksQuery(client))
  const acknowledgements = useQuery(openAcknowledgementsQuery(client))
  const reviews = useQuery(openReviewTasksQuery(client))
  if (tasks.isPending) return undefined
  return count(tasks.data) + count(acknowledgements.data) + count(reviews.data)
}
