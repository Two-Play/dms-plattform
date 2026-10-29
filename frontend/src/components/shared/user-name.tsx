import { useUser } from '@/api/users'

/**
 * A user shown by display name, with the user id as tooltip (DMS-110). Falls back to the id while
 * the name loads, for ids unknown in the tenant and when the user directory is unavailable.
 */
export function UserName({ id, className }: { id: string; className?: string }) {
  const user = useUser(id)
  const name = user.data?.displayName
  return (
    <span className={className} title={id} data-user-id={id}>
      {name ?? id}
    </span>
  )
}

/** Several users, separated by commas. */
export function UserNames({ ids }: { ids: readonly string[] }) {
  return (
    <>
      {ids.map((id, i) => (
        <span key={id}>
          {i > 0 && ', '}
          <UserName id={id} />
        </span>
      ))}
    </>
  )
}
