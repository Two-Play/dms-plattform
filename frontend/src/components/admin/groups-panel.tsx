import { Users } from 'lucide-react'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { toast } from '@/lib/toast'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  addGroupMember,
  createGroup,
  groupMembersQuery,
  groupsQuery,
  removeGroupMember,
  renameGroup,
  type Group,
  type GroupMember,
} from '@/api/groups'
import { formatDateTime } from '@/components/deputies/format'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/** Group keys as the backend accepts them (`Groups.KeyPattern`). */
export const groupKeyPattern = /^[a-z0-9][a-z0-9-]{0,62}$/

function CreateGroupForm({ onCreated }: { onCreated: (group: Group) => void }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [attempted, setAttempted] = useState(false)
  const keyProblem = groupKeyPattern.test(key.trim())
    ? undefined
    : 'Lowercase letters, digits and hyphens, starting with a letter or digit (at most 63).'
  const nameProblem = name.trim() === '' ? 'Enter a name.' : undefined

  const create = useMutation({
    mutationFn: () => createGroup(client, key.trim(), name.trim()),
    onSuccess: async (group) => {
      await queryClient.invalidateQueries({ queryKey: ['groups'] })
      setKey('')
      setName('')
      setAttempted(false)
      toast(`Group ${group.name} created.`)
      onCreated(group)
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (keyProblem ?? nameProblem) return
    create.mutate()
  }

  const keyError = attempted ? keyProblem : undefined
  const nameError = attempted ? nameProblem : undefined
  return (
    <form
      onSubmit={submit}
      className="grid gap-4 sm:grid-cols-[1fr_1fr_auto] sm:items-start"
      noValidate
    >
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-key`}>Key *</Label>
        <Input
          id={`${id}-key`}
          value={key}
          placeholder="e.g. hr-management"
          onChange={(e) => {
            setKey(e.target.value)
          }}
          aria-required
          aria-invalid={keyError ? true : undefined}
          aria-describedby={`${id}-key-hint`}
        />
        <p
          id={`${id}-key-hint`}
          role={keyError ? 'alert' : undefined}
          className={keyError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {keyError ?? 'Stored in documents; cannot be changed later.'}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-name`}>Name *</Label>
        <Input
          id={`${id}-name`}
          value={name}
          onChange={(e) => {
            setName(e.target.value)
          }}
          aria-required
          aria-invalid={nameError ? true : undefined}
          aria-describedby={nameError ? `${id}-name-hint` : undefined}
        />
        {nameError && (
          <p id={`${id}-name-hint`} role="alert" className="text-sm text-destructive">
            {nameError}
          </p>
        )}
      </div>
      <div className="grid gap-1.5 sm:pt-6">
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? 'Creating…' : 'Create group'}
        </Button>
      </div>
      {create.isError && <InlineError error={create.error} className="sm:col-span-3" />}
    </form>
  )
}

function RenameForm({ group }: { group: Group }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [name, setName] = useState(group.name)
  const rename = useMutation({
    mutationFn: () => renameGroup(client, group.key, name.trim()),
    onSuccess: async () => {
      toast(`Group renamed to ${name.trim()}.`)
      await queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
  const unchanged = name.trim() === group.name
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        if (name.trim() !== '' && !unchanged) rename.mutate()
      }}
      className="flex flex-wrap items-end gap-2"
    >
      <div className="grid min-w-48 flex-1 gap-1.5">
        <Label htmlFor={`${id}-name`}>Name</Label>
        <Input
          id={`${id}-name`}
          value={name}
          onChange={(e) => {
            setName(e.target.value)
          }}
        />
      </div>
      <Button
        type="submit"
        variant="outline"
        disabled={rename.isPending || unchanged || name.trim() === ''}
      >
        {rename.isPending ? 'Renaming…' : 'Rename'}
      </Button>
      {rename.isError && <InlineError error={rename.error} className="w-full" />}
    </form>
  )
}

/** Membership changes reload the members and the task inbox (participants are re-resolved). */
function useMembershipChange(
  group: Group,
  change: (userId: string) => Promise<GroupMember[]>,
  done: (userId: string) => string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: change,
    onSuccess: async (members, userId) => {
      toast(done(userId))
      queryClient.setQueryData(['groups', group.key, 'members'], members)
      await queryClient.invalidateQueries({ queryKey: ['workflow'] })
    },
  })
}

function AddMemberForm({ group }: { group: Group }) {
  const client = useApiClient()
  const id = useId()
  const [userId, setUserId] = useState('')
  const add = useMembershipChange(
    group,
    (user) => addGroupMember(client, group.key, user),
    (user) => `${user} added to ${group.name}.`,
  )
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        const user = userId.trim()
        if (user === '') return
        add.mutate(user, {
          onSuccess: () => {
            setUserId('')
          },
        })
      }}
      className="flex flex-wrap items-end gap-2"
    >
      <div className="grid min-w-48 flex-1 gap-1.5">
        <Label htmlFor={`${id}-user`}>User id</Label>
        <Input
          id={`${id}-user`}
          value={userId}
          onChange={(e) => {
            setUserId(e.target.value)
          }}
        />
      </div>
      <Button type="submit" disabled={add.isPending || userId.trim() === ''}>
        {add.isPending ? 'Adding…' : 'Add member'}
      </Button>
      {add.isError && <InlineError error={add.error} className="w-full" />}
    </form>
  )
}

function MemberTable({ group, members }: { group: Group; members: GroupMember[] }) {
  const client = useApiClient()
  const remove = useMembershipChange(
    group,
    (user) => removeGroupMember(client, group.key, user),
    (user) => `${user} removed from ${group.name}.`,
  )
  if (members.length === 0) {
    return (
      <EmptyState
        icon={Users}
        title="The group has no members yet."
        description="Add members by their user id."
      />
    )
  }
  return (
    <div className="space-y-2">
      {remove.isError && <InlineError error={remove.error} />}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">Members of {group.name}, current ones first</caption>
          <thead className="text-left text-xs text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="py-2 pr-4 font-medium">
                User
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                Added
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                Removed
              </th>
              <th scope="col" className="py-2 font-medium">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {members.map((m) => {
              const current = !m.removedAt
              return (
                <tr
                  key={`${m.userId}-${m.addedAt}`}
                  className={`border-b align-top last:border-0 ${current ? '' : 'text-muted-foreground'}`}
                >
                  <td className="py-2 pr-4 break-all">
                    {m.userId}{' '}
                    {!current && (
                      <Badge variant="outline" className="ml-1">
                        Former
                      </Badge>
                    )}
                  </td>
                  <td className="py-2 pr-4">
                    {formatDateTime(m.addedAt)}
                    <div className="text-xs text-muted-foreground">by {m.addedBy}</div>
                  </td>
                  <td className="py-2 pr-4">
                    {m.removedAt && (
                      <>
                        {formatDateTime(m.removedAt)}
                        <div className="text-xs text-muted-foreground">by {m.removedBy}</div>
                      </>
                    )}
                  </td>
                  <td className="py-2">
                    {current && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={remove.isPending}
                        aria-label={`Remove ${m.userId} from ${group.name}`}
                        onClick={() => {
                          remove.mutate(m.userId)
                        }}
                      >
                        Remove
                      </Button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function GroupDetail({ group }: { group: Group }) {
  const client = useApiClient()
  const members = useQuery(groupMembersQuery(client, group.key))
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{group.name}</h2>
        </CardTitle>
        <CardDescription>
          Key <code>{group.key}</code>. Members see the confidential documents naming the group.
          Open review and approval tasks of those documents are updated when members join or leave.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <RenameForm key={group.name} group={group} />
        <AddMemberForm group={group} />
        {members.isPending && <LoadingState label="Loading the members" />}
        {members.isError && (
          <ErrorState error={members.error} onRetry={() => void members.refetch()} />
        )}
        {members.data && <MemberTable group={group} members={members.data} />}
      </CardContent>
    </Card>
  )
}

/** Access groups for confidential documents (DMS-101, DMS-105): create, rename, members. */
export function GroupsPanel() {
  const client = useApiClient()
  const groups = useQuery(groupsQuery(client))
  const [selectedKey, setSelectedKey] = useState<string>()
  const selected = groups.data?.find((g) => g.key === selectedKey) ?? groups.data?.[0]

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>New group</CardTitle>
          <CardDescription>
            Groups are not deleted, since document versions keep naming them.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <CreateGroupForm
            onCreated={(group) => {
              setSelectedKey(group.key)
            }}
          />
        </CardContent>
      </Card>
      {groups.isPending && <LoadingState label="Loading the access groups" rows={4} />}
      {groups.isError && <ErrorState error={groups.error} onRetry={() => void groups.refetch()} />}
      {groups.data && groups.data.length === 0 && (
        <EmptyState
          icon={Users}
          title="No access groups yet."
          description="Create a group above, then add members to it."
        />
      )}
      {groups.data && selected && (
        <div className="grid gap-6 md:grid-cols-[14rem_1fr]">
          <nav aria-label="Access groups">
            <ul className="flex gap-1 overflow-x-auto md:flex-col">
              {groups.data.map((g) => (
                <li key={g.key}>
                  <button
                    type="button"
                    aria-current={g.key === selected.key ? 'true' : undefined}
                    onClick={() => {
                      setSelectedKey(g.key)
                    }}
                    className="w-full rounded-md px-3 py-2 text-left text-sm whitespace-nowrap hover:bg-accent aria-[current=true]:bg-accent aria-[current=true]:font-medium"
                  >
                    {g.name}
                    <span className="block text-xs text-muted-foreground">{g.key}</span>
                  </button>
                </li>
              ))}
            </ul>
          </nav>
          <GroupDetail key={selected.key} group={selected} />
        </div>
      )}
    </div>
  )
}
