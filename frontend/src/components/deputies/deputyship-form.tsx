import { InlineError } from '@/components/shared/error-state'
import { toast } from '@/lib/toast'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { createDeputyship, deputyRoles, type Deputyship } from '@/api/deputyships'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cachedUserName } from '@/api/users'
import { statusLabel } from '@/components/documents/status-label'
import { PersonPicker } from '@/components/shared/person-picker'
import { roleLabel } from '@/components/shared/role-label'
import { t } from '@/i18n'
import {
  checkDeputyship,
  emptyDeputyshipInput,
  toRequest,
  type DeputyshipInput,
  type Problems,
} from './deputyship-input'

/**
 * Sets a deputy for a limited period. With `withPrincipal` (tenant administrators) the principal
 * can be someone else; otherwise the caller is the principal.
 */
export function DeputyshipForm({
  withPrincipal = false,
  onCreated,
}: {
  withPrincipal?: boolean
  onCreated?: (created: Deputyship) => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [input, setInput] = useState<DeputyshipInput>(emptyDeputyshipInput)
  const [attempted, setAttempted] = useState(false)
  const problems = checkDeputyship(input)
  const shown = attempted ? problems : {}

  const create = useMutation({
    mutationFn: () => createDeputyship(client, toRequest(input)),
    onSuccess: async (created) => {
      toast(
        t('deputies.set', {
          deputy: cachedUserName(queryClient, created.deputy),
          principal: cachedUserName(queryClient, created.principal),
          status: statusLabel(created.status),
        }),
      )
      await queryClient.invalidateQueries({ queryKey: ['deputyships'] })
      setInput(emptyDeputyshipInput)
      setAttempted(false)
      onCreated?.(created)
    },
  })

  function update<K extends keyof DeputyshipInput>(key: K, value: DeputyshipInput[K]) {
    setInput((current) => ({ ...current, [key]: value }))
  }

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (Object.keys(problems).length > 0) return
    create.mutate()
  }

  const hint = (key: keyof Problems, text: string) => {
    const problem = shown[key]
    return (
      <p
        id={`${id}-${key}-hint`}
        role={problem ? 'alert' : undefined}
        className={problem ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
      >
        {problem ?? text}
      </p>
    )
  }

  return (
    <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2" noValidate>
      {withPrincipal && (
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-principal`}>{t('deputies.principalOptional')}</Label>
          <PersonPicker
            id={`${id}-principal`}
            value={input.principal}
            onChange={(userId) => {
              update('principal', userId)
            }}
            describedBy={`${id}-principal-hint`}
          />
          <p id={`${id}-principal-hint`} className="text-xs text-muted-foreground">
            {t('deputies.principalHint')}
          </p>
        </div>
      )}
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-deputy`}>{t('deputies.deputyRequired')}</Label>
        <PersonPicker
          id={`${id}-deputy`}
          value={input.deputy}
          onChange={(userId) => {
            update('deputy', userId)
          }}
          required
          invalid={Boolean(shown.deputy)}
          describedBy={`${id}-deputy-hint`}
        />
        {hint('deputy', t('deputies.deputyHint'))}
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-from`}>{t('deputies.fromOptional')}</Label>
        <Input
          id={`${id}-from`}
          type="datetime-local"
          value={input.validFrom}
          onChange={(e) => {
            update('validFrom', e.target.value)
          }}
          aria-invalid={shown.validFrom ? true : undefined}
          aria-describedby={`${id}-validFrom-hint`}
        />
        {hint('validFrom', t('deputies.fromHint'))}
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-until`}>{t('deputies.untilRequired')}</Label>
        <Input
          id={`${id}-until`}
          type="datetime-local"
          value={input.validUntil}
          onChange={(e) => {
            update('validUntil', e.target.value)
          }}
          aria-required
          aria-invalid={shown.validUntil ? true : undefined}
          aria-describedby={`${id}-validUntil-hint`}
        />
        {hint('validUntil', t('deputies.untilHint'))}
      </div>
      <fieldset className="grid gap-1.5">
        <legend className="mb-1.5 text-sm font-medium">{t('deputies.rolesOptional')}</legend>
        <div className="flex flex-wrap gap-x-4 gap-y-1">
          {deputyRoles.map((role) => (
            <label key={role} className="flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                checked={input.roles.includes(role)}
                onChange={(e) => {
                  update(
                    'roles',
                    e.target.checked
                      ? [...input.roles, role]
                      : input.roles.filter((r) => r !== role),
                  )
                }}
              />
              {roleLabel(role)}
            </label>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">{t('deputies.rolesHint')}</p>
      </fieldset>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-class`}>{t('deputies.classOptional')}</Label>
        <Input
          id={`${id}-class`}
          value={input.documentClass}
          onChange={(e) => {
            update('documentClass', e.target.value)
          }}
          aria-describedby={`${id}-class-hint`}
        />
        <p id={`${id}-class-hint`} className="text-xs text-muted-foreground">
          {t('deputies.classHint')}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-reason`}>{t('deputies.reasonOptional')}</Label>
        <Input
          id={`${id}-reason`}
          value={input.reason}
          placeholder={t('deputies.reasonPlaceholder')}
          onChange={(e) => {
            update('reason', e.target.value)
          }}
        />
      </div>
      <div className="flex flex-wrap items-center gap-3 sm:col-span-2">
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? t('deputies.saving') : t('deputies.submit')}
        </Button>
        {create.isError && <InlineError error={create.error} />}
      </div>
    </form>
  )
}
