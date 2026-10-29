import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useId, useState, type KeyboardEvent } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import {
  MIN_PERSON_QUERY,
  personQuery,
  personSearchQuery,
  useUser,
  useUserLabel,
  type UserMatch,
} from '@/api/users'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { t } from '@/i18n'
import { useDebouncedValue } from '@/lib/use-debounced-value'

/** Delay after the last keystroke before the directory is searched. */
export const PERSON_SEARCH_DEBOUNCE_MS = 250

export interface PersonPickerProps {
  /** Id of the text field, for an outside `<Label htmlFor>`. */
  id: string
  /** The picked user id; empty if nobody is picked. */
  value: string
  /** Called with the picked user id, or with '' when the selection is cleared or the text changes. */
  onChange: (userId: string) => void
  /** Accessible name if there is no outside label. */
  ariaLabel?: string
  /** Ids of further descriptions (hint, error) of the field. */
  describedBy?: string
  invalid?: boolean
  required?: boolean
  disabled?: boolean
}

/**
 * "Select a person" (DMS-111): an accessible combobox (WAI-ARIA combobox with a list box) that
 * searches the tenant's people by name or email and stores the user id. Shows name and email of
 * hits and of the picked person. Keyboard: arrow keys move through the hits, Enter picks, Escape
 * closes the list (or clears the text). Loading, empty and error states are announced in a live
 * region. When the user directory cannot be asked, the typed text can be taken as user id, as
 * before DMS-111.
 */
export function PersonPicker({
  id,
  value,
  onChange,
  ariaLabel,
  describedBy,
  invalid,
  required,
  disabled,
}: PersonPickerProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const own = useId()
  const listId = `${own}-list`
  const hintId = `${own}-hint`
  const statusId = `${own}-status`
  const selectedId = `${own}-selected`

  const [text, setText] = useState('')
  const [typing, setTyping] = useState(false)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)

  const debounced = useDebouncedValue(text, PERSON_SEARCH_DEBOUNCE_MS)
  const query = typing ? personQuery(debounced) : undefined
  const search = useQuery({
    ...personSearchQuery(client, queryClient, query ?? ''),
    enabled: query !== undefined,
  })
  const selected = useUser(value === '' ? undefined : value)

  const hits: UserMatch[] = query !== undefined ? (search.data ?? []) : []
  const activeIndex = active < hits.length ? active : -1
  const pending =
    typing && personQuery(text) !== undefined && (text !== debounced || search.isFetching)
  const unavailable =
    search.error instanceof ApiRequestError && search.error.code === 'user_directory_unavailable'
  const expanded = open && typing && hits.length > 0
  const selectedName = selected.data?.displayName ?? value
  const shown = typing ? text : value !== '' ? selectedName : text

  function pick(person: UserMatch) {
    queryClient.setQueryData(['users', person.id], person)
    onChange(person.id)
    setText('')
    setTyping(false)
    setOpen(false)
    setActive(-1)
  }

  function takeTextAsId() {
    const typed = text.trim()
    if (typed === '') return
    onChange(typed)
    setText('')
    setTyping(false)
    setOpen(false)
  }

  function clear() {
    onChange('')
    setText('')
    setTyping(false)
    setOpen(false)
    setActive(-1)
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault()
        setOpen(true)
        if (hits.length > 0) setActive(activeIndex < hits.length - 1 ? activeIndex + 1 : 0)
        break
      case 'ArrowUp':
        e.preventDefault()
        setOpen(true)
        if (hits.length > 0) setActive(activeIndex > 0 ? activeIndex - 1 : hits.length - 1)
        break
      case 'Home':
      case 'End':
        if (expanded) {
          e.preventDefault()
          setActive(e.key === 'Home' ? 0 : hits.length - 1)
        }
        break
      case 'Enter': {
        const person = expanded && activeIndex >= 0 ? hits[activeIndex] : undefined
        if (person) {
          e.preventDefault()
          pick(person)
        } else if (typing) {
          // Never submit the surrounding form with a half-typed name.
          e.preventDefault()
        }
        break
      }
      case 'Escape':
        if (expanded) {
          e.preventDefault()
          e.stopPropagation()
          setOpen(false)
          setActive(-1)
        } else if (typing && text !== '') {
          e.preventDefault()
          e.stopPropagation()
          setText('')
        }
        break
    }
  }

  let status = ''
  if (typing) {
    if (personQuery(text) === undefined) status = t('people.hint', { count: MIN_PERSON_QUERY })
    else if (pending) status = t('people.searching')
    else if (search.isError) status = unavailable ? t('people.unavailable') : search.error.message
    else if (search.isSuccess && hits.length === 0) {
      status = t('people.none', { query: debounced.trim() })
    } else if (search.isSuccess) status = t('people.found', { count: hits.length })
  }
  const showHint = !typing && value === ''

  return (
    <div className="relative grid gap-1">
      <div className="relative">
        <Input
          id={id}
          type="text"
          role="combobox"
          autoComplete="off"
          spellCheck={false}
          maxLength={100}
          placeholder={t('people.placeholder')}
          aria-label={ariaLabel}
          aria-autocomplete="list"
          aria-expanded={expanded}
          aria-controls={listId}
          aria-activedescendant={
            expanded && activeIndex >= 0 ? `${own}-option-${activeIndex}` : undefined
          }
          aria-describedby={
            [describedBy, value !== '' && !typing ? selectedId : undefined, hintId]
              .filter(Boolean)
              .join(' ') || undefined
          }
          aria-invalid={invalid ? true : undefined}
          aria-required={required ? true : undefined}
          disabled={disabled}
          className={value !== '' && !typing ? 'pr-8' : undefined}
          value={shown}
          onChange={(e) => {
            setText(e.target.value)
            setTyping(true)
            setOpen(true)
            setActive(-1)
            if (value !== '') onChange('')
          }}
          onFocus={() => {
            if (typing) setOpen(true)
          }}
          onBlur={() => {
            setOpen(false)
          }}
          onKeyDown={onKeyDown}
        />
        {value !== '' && !typing && !disabled && (
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            className="absolute top-1/2 right-1 -translate-y-1/2"
            aria-label={t('people.clear')}
            title={t('people.clear')}
            onClick={clear}
          >
            <X aria-hidden="true" />
          </Button>
        )}
        <ul
          id={listId}
          role="listbox"
          aria-label={t('people.results')}
          hidden={!expanded}
          className="absolute top-full right-0 left-0 z-20 mt-1 max-h-72 overflow-y-auto rounded-lg border bg-popover p-1 text-popover-foreground shadow-md"
        >
          {hits.map((person, index) => (
            <li
              key={person.id}
              id={`${own}-option-${index}`}
              role="option"
              aria-selected={index === activeIndex}
              className="cursor-pointer rounded-md px-2 py-1.5 text-sm aria-selected:bg-muted"
              onMouseDown={(e) => {
                // Keeps the focus in the text field, so the click is not lost to its blur.
                e.preventDefault()
              }}
              onMouseEnter={() => {
                setActive(index)
              }}
              onClick={() => {
                pick(person)
              }}
            >
              <span className="block font-medium">{person.displayName}</span>
              {person.email && (
                <span className="block text-xs text-muted-foreground">{person.email}</span>
              )}
            </li>
          ))}
        </ul>
      </div>
      {value !== '' && !typing && (
        <p id={selectedId} className="text-xs text-muted-foreground" data-user-id={value}>
          {selected.data?.email ?? value}
        </p>
      )}
      <p id={hintId} className="sr-only">
        {showHint ? t('people.hint', { count: MIN_PERSON_QUERY }) : ''}
      </p>
      <p
        id={statusId}
        role="status"
        className={
          search.isError && typing ? 'text-xs text-destructive' : 'text-xs text-muted-foreground'
        }
      >
        {status}
      </p>
      {typing && unavailable && text.trim() !== '' && (
        // The directory is down or not configured: the id can still be typed, as before DMS-111.
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground">{t('people.unavailableHint')}</span>
          <Button type="button" size="sm" variant="outline" onClick={takeTextAsId}>
            {t('people.useId', { id: text.trim() })}
          </Button>
        </div>
      )}
    </div>
  )
}

export interface PeoplePickerProps extends Omit<PersonPickerProps, 'value' | 'onChange'> {
  /** The picked user ids, in the order picked. */
  value: readonly string[]
  onChange: (userIds: string[]) => void
}

/**
 * Several people (e.g. a person field that takes more than one value): the picked people as a list
 * with a remove button each, and a [[PersonPicker]] to add one more.
 */
export function PeoplePicker({ value, onChange, disabled, ...picker }: PeoplePickerProps) {
  return (
    <div className="grid gap-1.5">
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" aria-label={t('people.picked')}>
          {value.map((userId) => (
            <li
              key={userId}
              className="flex items-center gap-1 rounded-md border bg-muted/50 py-0.5 pr-0.5 pl-2 text-sm"
            >
              <UserName id={userId} />
              {!disabled && (
                <PickedPersonRemove
                  userId={userId}
                  onRemove={() => {
                    onChange(value.filter((id) => id !== userId))
                  }}
                />
              )}
            </li>
          ))}
        </ul>
      )}
      <PersonPicker
        {...picker}
        disabled={disabled}
        value=""
        onChange={(userId) => {
          if (userId !== '' && !value.includes(userId)) onChange([...value, userId])
        }}
      />
    </div>
  )
}

/** "Remove", labelled with the person's name for screen readers. */
function PickedPersonRemove({ userId, onRemove }: { userId: string; onRemove: () => void }) {
  const name = useUserLabel(userId) ?? userId
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-xs"
      aria-label={t('people.remove', { name })}
      title={t('people.remove', { name })}
      onClick={onRemove}
    >
      <X aria-hidden="true" />
    </Button>
  )
}
