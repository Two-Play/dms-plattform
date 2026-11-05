import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { TemplateField } from '@/api/templates'
import { DocumentForm } from '@/components/documents/document-form'
import type { FieldInput, FormInputs } from '@/components/documents/field-values'
import { setLocale } from '@/i18n'
import { PeoplePicker, PersonPicker } from './person-picker'

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface Init {
  params?: { query?: Record<string, unknown> }
}

const people = [
  { id: 'u-1', displayName: 'Anna Author', email: 'anna@example.com' },
  { id: 'u-2', displayName: 'Andreas Approver', email: 'andreas@example.com' },
  { id: 'u-3', displayName: 'Rita Reviewer' },
]

/** Like the backend: every word is part of the name or the email. */
function directory(path: string, init?: Init): Reply {
  if (path === '/api/v1/users/search') {
    const q = (init?.params?.query?.q as string | undefined) ?? ''
    const words = q.toLowerCase().split(/\s+/)
    const items = people.filter((p) => {
      const text = `${p.displayName} ${p.email ?? ''}`.toLowerCase()
      return words.every((w) => text.includes(w))
    })
    return ok({ items, total: items.length })
  }
  if (path === '/api/v1/users') {
    const ids = ((init?.params?.query?.ids as string | undefined) ?? '').split(',')
    return ok(ids.map((id) => people.find((p) => p.id === id) ?? { id }))
  }
  return failed(404, { code: 'not_found', message: `unexpected ${path}` })
}

function renderWith(ui: React.ReactNode, get: (path: string, init?: Init) => Reply = directory) {
  const client = { GET: vi.fn(get) } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>{ui}</ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get: client.GET as unknown as ReturnType<typeof vi.fn>, user: userEvent.setup() }
}

/** A labelled picker that keeps its value, like the forms using it. */
function Picker({ onChange, initial = '' }: { onChange?: (id: string) => void; initial?: string }) {
  const [value, setValue] = useState(initial)
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
      }}
    >
      <label htmlFor="person">Owner</label>
      <PersonPicker
        id="person"
        value={value}
        onChange={(id) => {
          setValue(id)
          onChange?.(id)
        }}
      />
      <span data-testid="value">{value}</span>
    </form>
  )
}

const combobox = () => screen.getByRole('combobox', { name: 'Owner' })
const searches = (get: ReturnType<typeof vi.fn>) =>
  get.mock.calls.filter(([path]) => path === '/api/v1/users/search')

describe('PersonPicker (DMS-111)', () => {
  it('searches by name once enough is typed and picks a person with the mouse', async () => {
    const onChange = vi.fn()
    const { get, user } = renderWith(<Picker onChange={onChange} />)

    expect(combobox()).toHaveAttribute('aria-expanded', 'false')
    expect(combobox()).toHaveAttribute('aria-autocomplete', 'list')
    await user.type(combobox(), 'a')
    expect(screen.getByRole('status')).toHaveTextContent(
      'Type at least 2 characters of the name or email.',
    )
    await user.type(combobox(), 'n')

    const list = await screen.findByRole('listbox', { name: 'People found' })
    const options = within(list).getAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual([
      'Anna Authoranna@example.com',
      'Andreas Approverandreas@example.com',
    ])
    expect(screen.getByRole('status')).toHaveTextContent('2 people found.')
    expect(combobox()).toHaveAttribute('aria-expanded', 'true')
    expect(combobox()).toHaveAttribute('aria-controls', list.id)
    expect(searches(get)).toEqual([
      [
        '/api/v1/users/search',
        expect.objectContaining({ params: { query: { q: 'an', offset: 0, limit: 20 } } }),
      ],
    ])

    await user.click(within(list).getByRole('option', { name: /Andreas Approver/ }))
    expect(onChange).toHaveBeenLastCalledWith('u-2')
    expect(screen.getByTestId('value')).toHaveTextContent('u-2')
    expect(combobox()).toHaveValue('Andreas Approver')
    expect(combobox()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByText('andreas@example.com')).toBeInTheDocument()
    // The name came with the hit: no extra lookup.
    expect(get.mock.calls.some(([path]) => path === '/api/v1/users')).toBe(false)
  })

  it('works with the keyboard: arrows move, Enter picks, Escape closes and clears', async () => {
    const { user } = renderWith(<Picker />)

    await user.type(combobox(), 'an')
    await screen.findByRole('listbox')
    await user.keyboard('{ArrowDown}')
    const first = screen.getByRole('option', { name: /Anna Author/ })
    expect(combobox()).toHaveAttribute('aria-activedescendant', first.id)
    expect(first).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(combobox()).toHaveAttribute('aria-activedescendant', first.id)
    await user.keyboard('{ArrowUp}')
    expect(combobox()).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: /Andreas Approver/ }).id,
    )

    await user.keyboard('{Escape}')
    expect(combobox()).toHaveAttribute('aria-expanded', 'false')
    expect(combobox()).toHaveValue('an')
    await user.keyboard('{Escape}')
    expect(combobox()).toHaveValue('')

    await user.type(combobox(), 'rita')
    await screen.findByRole('option', { name: /Rita Reviewer/ })
    await user.keyboard('{ArrowDown}{Enter}')
    expect(screen.getByTestId('value')).toHaveTextContent('u-3')
    expect(combobox()).toHaveValue('Rita Reviewer')

    // Typing again drops the selection until a person is picked.
    await user.type(combobox(), 'x')
    expect(screen.getByTestId('value')).toHaveTextContent('')
  })

  it('shows the picked person by name and clears the selection', async () => {
    const onChange = vi.fn()
    const { user } = renderWith(<Picker initial="u-1" onChange={onChange} />)

    await waitFor(() => {
      expect(combobox()).toHaveValue('Anna Author')
    })
    expect(screen.getByText('anna@example.com')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear selection' }))
    expect(onChange).toHaveBeenLastCalledWith('')
    expect(combobox()).toHaveValue('')
  })

  it('says when nobody is found', async () => {
    const { user } = renderWith(<Picker />)
    await user.type(combobox(), 'zz')
    expect(await screen.findByText('Nobody found for “zz”.')).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
    expect(combobox()).toHaveAttribute('aria-expanded', 'false')
  })

  it('shows the searching state while the directory answers', async () => {
    let answer: ((reply: Awaited<Reply>) => void) | undefined
    const { user } = renderWith(<Picker />, (path, init) =>
      path === '/api/v1/users/search'
        ? new Promise((resolve) => {
            answer = resolve
          })
        : directory(path, init),
    )
    await user.type(combobox(), 'anna')
    expect(await screen.findByText('Searching…')).toHaveAttribute('role', 'status')
    await waitFor(() => {
      expect(answer).toBeDefined()
    })
    expect(screen.getByRole('status')).toHaveTextContent('Searching…')
    answer?.({
      data: { items: [people[0]], total: 1 },
      error: undefined,
      response: new Response(null, { status: 200 }),
    })
    expect(await screen.findByText('1 person found.')).toBeInTheDocument()
  })

  it('offers to take the typed user id when the directory cannot be asked', async () => {
    const onChange = vi.fn()
    const { user } = renderWith(<Picker onChange={onChange} />, (path, init) =>
      path === '/api/v1/users/search'
        ? failed(503, {
            code: 'user_directory_unavailable',
            message: 'User names cannot be looked up right now: no user directory is configured',
          })
        : directory(path, init),
    )
    await user.type(combobox(), 'abc-1')
    expect(await screen.findByText('People cannot be searched right now.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use “abc-1” as user id' }))
    expect(onChange).toHaveBeenLastCalledWith('abc-1')
    expect(screen.getByTestId('value')).toHaveTextContent('abc-1')
  })

  it('shows other errors, e.g. a caller without a role', async () => {
    const { user } = renderWith(<Picker />, () =>
      failed(403, {
        code: 'forbidden',
        message: 'You are not allowed to do this: no active role assignment covers this resource.',
      }),
    )
    await user.type(combobox(), 'anna')
    expect(await screen.findByText(/You are not allowed to do this/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /as user id/ })).not.toBeInTheDocument()
  })

  it('finds people by a part of the name, not only by its start (DMS-125)', async () => {
    const { user } = renderWith(<Picker />)
    await user.type(combobox(), 'eview')
    expect(await screen.findByRole('option', { name: /Rita Reviewer/ })).toBeInTheDocument()
  })

  it('speaks German', async () => {
    setLocale('de')
    const { user } = renderWith(<Picker initial="u-3" />)

    await waitFor(() => {
      expect(combobox()).toHaveValue('Rita Reviewer')
    })
    await user.click(screen.getByRole('button', { name: 'Auswahl entfernen' }))
    expect(combobox()).toHaveAttribute('placeholder', 'Name oder E-Mail …')
    await user.type(combobox(), 'a')
    expect(screen.getByRole('status')).toHaveTextContent(
      'Mindestens 2 Zeichen des Namens oder der E-Mail eingeben.',
    )
    await user.type(combobox(), 'n')
    expect(await screen.findByRole('listbox', { name: 'Gefundene Personen' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('2 Personen gefunden.')
    await user.clear(combobox())
    await user.type(combobox(), 'zz')
    expect(await screen.findByText('Niemand gefunden für „zz“.')).toBeInTheDocument()
  })
})

/** 45 people on pages of 20, as the backend answers with `offset` and `nextOffset`. */
const crowd = Array.from({ length: 45 }, (_, i) => ({ id: `p-${i}`, displayName: `Person ${i}` }))

function pagedDirectory(reach = crowd.length) {
  return (path: string, init?: Init): Reply => {
    if (path !== '/api/v1/users/search') return directory(path, init)
    const offset = (init?.params?.query?.offset as number | undefined) ?? 0
    const next = offset + 20
    return ok({
      items: crowd.slice(offset, next),
      total: crowd.length,
      nextOffset: next < Math.min(reach, crowd.length) ? next : undefined,
    })
  }
}

const loadMore = () => screen.getByRole('option', { name: 'Load more' })

describe('PersonPicker with more hits than one page (DMS-125)', () => {
  it('says how many are shown and loads the next page with the mouse', async () => {
    const { get, user } = renderWith(<Picker />, pagedDirectory())
    await user.type(combobox(), 'person')

    await screen.findAllByText('20 of 45 people shown.')
    expect(screen.getByRole('status')).toHaveTextContent('20 of 45 people shown.')
    expect(screen.getAllByRole('option')).toHaveLength(21)
    // The open list covers the status line: it says the same itself, hidden from screen readers.
    const footer = screen
      .getAllByText('20 of 45 people shown.')
      .find((e) => e.getAttribute('aria-hidden') === 'true')
    expect(footer).toBeVisible()
    expect(screen.getByRole('option', { name: 'Person 0' })).toHaveAttribute('aria-setsize', '45')

    await user.click(loadMore())
    expect(await screen.findByRole('option', { name: 'Person 20' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('40 of 45 people shown.')
    expect(combobox()).toHaveAttribute('aria-expanded', 'true')
    expect(searches(get).map(([, init]) => (init as Init).params?.query?.offset)).toEqual([0, 20])

    await user.click(loadMore())
    expect(await screen.findByText('45 people found.')).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('option', { name: 'Load more' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'Person 44' }))
    expect(screen.getByTestId('value')).toHaveTextContent('p-44')
  })

  it('works with the keyboard: End reaches the row, Enter loads and moves to the first new hit', async () => {
    const scroll = vi.spyOn(Element.prototype, 'scrollIntoView')
    const { user } = renderWith(<Picker />, pagedDirectory())
    await user.type(combobox(), 'person')
    await screen.findByRole('listbox')

    await user.keyboard('{End}')
    expect(combobox()).toHaveAttribute('aria-activedescendant', loadMore().id)
    expect(scroll).toHaveBeenCalledWith({ block: 'nearest' })
    scroll.mockClear()
    await user.keyboard('{Enter}')

    const first = await screen.findByRole('option', { name: 'Person 20' })
    expect(combobox()).toHaveFocus()
    expect(combobox()).toHaveAttribute('aria-activedescendant', first.id)
    expect(scroll.mock.contexts).toContain(first)
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('value')).toHaveTextContent('p-20')
    scroll.mockRestore()
  })

  it('shows that more is loading and keeps the hits', async () => {
    let answer: ((reply: Awaited<Reply>) => void) | undefined
    const paged = pagedDirectory()
    const { user } = renderWith(<Picker />, (path, init) =>
      init?.params?.query?.offset === 20
        ? new Promise((resolve) => {
            answer = resolve
          })
        : paged(path, init),
    )
    await user.type(combobox(), 'person')
    await screen.findAllByText('20 of 45 people shown.')
    await user.click(loadMore())

    const row = await screen.findByRole('option', { name: 'Loading more people…' })
    expect(row).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Loading more people…')
    expect(screen.getByRole('option', { name: 'Person 19' })).toBeInTheDocument()
    answer?.(await paged('/api/v1/users/search', { params: { query: { offset: 20 } } }))
    expect(await screen.findByRole('option', { name: 'Person 39' })).toBeInTheDocument()
  })

  it('keeps the hits when loading more fails, says why and can try again', async () => {
    let down = true
    const paged = pagedDirectory()
    const { user } = renderWith(<Picker />, (path, init) =>
      down && init?.params?.query?.offset === 20
        ? failed(503, { code: 'user_directory_unavailable', message: 'down' })
        : paged(path, init),
    )
    await user.type(combobox(), 'person')
    await screen.findAllByText('20 of 45 people shown.')
    await user.click(loadMore())

    const retry = await screen.findByRole('option', { name: 'Try loading more again' })
    expect(screen.getByRole('status')).toHaveTextContent('People cannot be searched right now.')
    // The open list covers the status line, so it says so as well.
    expect(
      screen
        .getAllByText('People cannot be searched right now.')
        .find((e) => e.getAttribute('aria-hidden') === 'true'),
    ).toBeVisible()
    expect(screen.getByRole('option', { name: 'Person 0' })).toBeInTheDocument()
    // The directory did answer before: no offer to take the typed text as a user id.
    expect(screen.queryByRole('button', { name: /as user id/ })).not.toBeInTheDocument()

    down = false
    await user.click(retry)
    expect(await screen.findByRole('option', { name: 'Person 20' })).toBeInTheDocument()
  })

  it('asks to refine the search where paging ends', async () => {
    const { user } = renderWith(<Picker />, pagedDirectory(20))
    await user.type(combobox(), 'person')
    expect(
      await screen.findAllByText('20 of 45 people shown. Refine your search to find the others.'),
    ).toHaveLength(2)
    expect(screen.queryByRole('option', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('lists a person once if the same hit comes on two pages', async () => {
    const { user } = renderWith(<Picker />, (path, init) => {
      if (path !== '/api/v1/users/search') return directory(path, init)
      return init?.params?.query?.offset === 2
        ? ok({ items: [people[1], people[2]], total: 4 })
        : ok({ items: [people[0], people[1]], total: 4, nextOffset: 2 })
    })
    await user.type(combobox(), 'an')
    await screen.findAllByText('2 of 4 people shown.')
    await user.click(loadMore())
    await screen.findByRole('option', { name: /Rita Reviewer/ })
    expect(screen.getAllByRole('option', { name: /Andreas Approver/ })).toHaveLength(1)
  })

  it('speaks German', async () => {
    setLocale('de')
    const { user } = renderWith(<Picker />, pagedDirectory())
    await user.type(combobox(), 'person')
    await screen.findAllByText('20 von 45 Personen angezeigt.')
    expect(screen.getByRole('status')).toHaveTextContent('20 von 45 Personen angezeigt.')
    expect(screen.getByRole('option', { name: 'Mehr laden' })).toBeInTheDocument()
  })
})

describe('people who cannot be picked (DMS-125)', () => {
  function Group({ onChange }: { onChange: (id: string) => void }) {
    return (
      <>
        <label htmlFor="member">Member</label>
        <PersonPicker
          id="member"
          value=""
          onChange={onChange}
          excluded={{ ids: ['u-1'], label: 'Already a member' }}
        />
      </>
    )
  }

  it('marks them; neither mouse nor Enter picks them, and the others stay pickable', async () => {
    const onChange = vi.fn()
    const { user } = renderWith(<Group onChange={onChange} />)
    const input = screen.getByRole('combobox', { name: 'Member' })
    await user.type(input, 'an')

    const member = await screen.findByRole('option', { name: /Anna Author.*Already a member/ })
    expect(member).toHaveAttribute('aria-disabled', 'true')
    await user.click(member)
    await user.keyboard('{ArrowDown}')
    // The mouse rests on the marked person: the arrow moved on from there.
    await user.keyboard('{ArrowUp}{Enter}')
    expect(input).toHaveAttribute('aria-activedescendant', member.id)
    expect(onChange).not.toHaveBeenCalled()

    await user.click(screen.getByRole('option', { name: /Andreas Approver/ }))
    expect(onChange).toHaveBeenLastCalledWith('u-2')
  })
})

describe('PeoplePicker (DMS-111)', () => {
  it('adds several people and removes one', async () => {
    function Several() {
      const [ids, setIds] = useState<string[]>([])
      return (
        <>
          <label htmlFor="people">Reviewers</label>
          <PeoplePicker id="people" value={ids} onChange={setIds} />
          <span data-testid="ids">{ids.join(',')}</span>
        </>
      )
    }
    const { user } = renderWith(<Several />)
    const input = screen.getByRole('combobox', { name: 'Reviewers' })

    await user.type(input, 'anna')
    await user.click(await screen.findByRole('option', { name: /Anna Author/ }))
    expect(input).toHaveValue('')
    await user.type(input, 'rita')
    await user.click(await screen.findByRole('option', { name: /Rita Reviewer/ }))
    expect(screen.getByTestId('ids')).toHaveTextContent('u-1,u-3')

    await user.clear(input)
    await user.type(input, 'anna')
    const again = await screen.findByRole('option', { name: /Anna Author.*Already selected/ })
    expect(again).toHaveAttribute('aria-disabled', 'true')
    await user.clear(input)

    const picked = screen.getByRole('list', { name: 'Selected people' })
    expect(within(picked).getByText('Anna Author')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Remove Anna Author' }))
    expect(screen.getByTestId('ids')).toHaveTextContent('u-3')
  })
})

describe('person fields of the document form (DMS-111)', () => {
  const owner: TemplateField = {
    key: 'owner',
    label: 'Owner',
    fieldType: { type: 'PersonRef' },
    multiple: false,
    required: true,
    usedInTemplate: true,
  }

  it('pick a person by name and store the user id as the field input', async () => {
    const changes: [string, FieldInput][] = []
    function Form() {
      const [inputs, setInputs] = useState<FormInputs>({ owner: '' })
      return (
        <DocumentForm
          fields={[owner]}
          inputs={inputs}
          errors={{}}
          onChange={(key, input) => {
            changes.push([key, input])
            setInputs((current) => ({ ...current, [key]: input }))
          }}
        />
      )
    }
    const { user } = renderWith(<Form />)

    const field = screen.getByRole('combobox', { name: /Owner/ })
    expect(field).toHaveAttribute('aria-required', 'true')
    await user.type(field, 'andreas')
    await user.click(await screen.findByRole('option', { name: /Andreas Approver/ }))
    expect(changes.at(-1)).toEqual(['owner', 'u-2'])
    expect(field).toHaveValue('Andreas Approver')
  })
})
