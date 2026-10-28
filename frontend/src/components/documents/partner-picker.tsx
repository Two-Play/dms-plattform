import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  ApiRequestError,
  MIN_QUERY_LENGTH,
  partnerSearchQuery,
  UNAVAILABLE_MESSAGE,
  type Partner,
  type ReferenceField,
} from '@/api/partners'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useDebouncedValue } from '@/lib/use-debounced-value'

/** Delay after the last keystroke before the partner master is searched. */
export const SEARCH_DEBOUNCE_MS = 300

interface PartnerPickerProps {
  reference: ReferenceField
  selected: Partner | undefined
  onSelect: (partner: Partner | undefined) => void
}

/**
 * Picks the entry of a reference field from the partner master (US-03.1): search by name or
 * identifier, take a hit over, change or remove it. When the partner master is not reachable, the
 * author is told so and can carry on without it.
 */
export function PartnerPicker({ reference, selected, onSelect }: PartnerPickerProps) {
  const client = useApiClient()
  const [text, setText] = useState('')
  const [searching, setSearching] = useState(selected === undefined)
  const query = useDebouncedValue(text, SEARCH_DEBOUNCE_MS)
  const search = useQuery({
    ...partnerSearchQuery(client, query),
    enabled: searching && query.trim().length >= MIN_QUERY_LENGTH,
  })
  const id = `reference-${reference.key}`
  const unavailable =
    search.error instanceof ApiRequestError && search.error.partnerDirectoryUnavailable
  const errorMessage = search.error
    ? unavailable
      ? search.error.message || UNAVAILABLE_MESSAGE
      : search.error.message
    : undefined

  if (selected && !searching) {
    return (
      <div className="space-y-1.5 rounded-lg border p-3" data-reference={reference.key}>
        <p className="text-sm font-medium">{reference.label}</p>
        <p className="text-sm">
          {selected.name}{' '}
          {selected.id && <span className="text-muted-foreground">({selected.id})</span>}
        </p>
        <p className="text-xs text-muted-foreground">{selected.address}</p>
        <div className="flex gap-2 pt-1">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setSearching(true)
            }}
          >
            Change partner
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              onSelect(undefined)
              setText('')
              setSearching(true)
            }}
          >
            Remove partner
          </Button>
        </div>
      </div>
    )
  }

  const hits = search.data ?? []
  return (
    <div className="space-y-1.5 rounded-lg border p-3" data-reference={reference.key}>
      <Label htmlFor={id}>{reference.label}</Label>
      <Input
        id={id}
        type="search"
        placeholder="Search the partner master by name or number"
        value={text}
        aria-describedby={`${id}-hint`}
        onChange={(e) => {
          setText(e.target.value)
        }}
      />
      <p id={`${id}-hint`} className="text-xs text-muted-foreground">
        Name and address are taken over from the partner master and cannot be edited here.
      </p>
      {errorMessage && (
        <p role="alert" className="text-xs text-destructive">
          {errorMessage}
        </p>
      )}
      {search.isFetching && <p className="text-xs text-muted-foreground">Searching…</p>}
      {search.isSuccess && hits.length === 0 && (
        <p className="text-xs text-muted-foreground">No partner found.</p>
      )}
      {hits.length > 0 && (
        <ul className="divide-y rounded-md border" aria-label={`${reference.label} hits`}>
          {hits.map((partner) => (
            <li key={partner.id}>
              <button
                type="button"
                className="w-full px-3 py-2 text-left text-sm hover:bg-muted focus-visible:bg-muted focus-visible:outline-none"
                onClick={() => {
                  onSelect(partner)
                  setSearching(false)
                }}
              >
                <span className="font-medium">{partner.name}</span>{' '}
                <span className="text-muted-foreground">({partner.id})</span>
                <span className="block text-xs text-muted-foreground">{partner.address}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {selected && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => {
            setSearching(false)
          }}
        >
          Keep {selected.name}
        </Button>
      )}
    </div>
  )
}
