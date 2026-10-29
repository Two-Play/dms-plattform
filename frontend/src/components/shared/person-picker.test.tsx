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

/** Like the backend: every word starts a part of the name or the email. */
function directory(path: string, init?: Init): Reply {
  if (path === '/api/v1/users/search') {
    const q = (init?.params?.query?.q as string | undefined) ?? ''
    const words = q.toLowerCase().split(/\s+/)
    return ok(
      people.filter((p) => {
        const parts = [...p.displayName.toLowerCase().split(' '), p.email ?? '']
        return words.every((w) => parts.some((part) => part.startsWith(w)))
      }),
    )
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
        expect.objectContaining({ params: { query: { q: 'an', limit: 10 } } }),
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
      data: [people[0]],
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
