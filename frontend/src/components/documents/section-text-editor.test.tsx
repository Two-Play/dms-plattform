import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Link,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { useEffect, useState } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DeviationRequest, Deviations } from '@/api/deviations'
import type { DocumentVersionView } from '@/api/partners'
import { setLocale } from '@/i18n'
import { DeviationsPanel } from './deviations-panel'

beforeAll(() => {
  // jsdom has no layout; ProseMirror asks for coordinates when it maps the caret while typing.
  const none: DOMRect[] = []
  Range.prototype.getBoundingClientRect = () => new DOMRect()
  Range.prototype.getClientRects = () => Object.assign(none, { item: () => null })
  document.elementFromPoint = () => null
})

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d9'

const paragraph = (value: string) => ({
  type: 'Paragraph' as const,
  content: [{ type: 'Text' as const, value, marks: [] }],
})

const parties = { id: 'parties', title: 'Parties', locked: true, blocks: [paragraph('Between us')] }
const liability = (text = 'Liability is limited.') => ({
  id: 'liability',
  title: 'Liability',
  locked: true,
  blocks: [paragraph(text)],
})
const terms = { id: 'terms', title: 'Terms', locked: false, blocks: [paragraph('One year')] }

function version(extra: Partial<DocumentVersionView> = {}): DocumentVersionView {
  return {
    id: 'v1',
    version: '1.0-draft.2',
    workingState: 2,
    status: 'Draft',
    changeKind: 'Major',
    requiresAcknowledgement: false,
    scope: '',
    classVersion: 1,
    templateId: '7c1e2f0a-0000-4000-8000-000000000001',
    templateVersion: 1,
    content: { sections: [parties, liability(), terms], computed: [], references: [] },
    values: {},
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: true,
    ...extra,
  }
}

const request: DeviationRequest = {
  id: 'r1',
  sectionId: 'liability',
  reason: 'The customer insists on a cap',
  requestedBy: 'author-1',
  requestedAt: '2026-11-02T09:00:00Z',
  active: true,
}

function report(extra: Partial<Deviations> = {}): Deviations {
  return {
    versionId: 'v1',
    version: '1.0-draft.2',
    status: 'Draft',
    requiresExtraReview: true,
    requests: [request],
    deviations: [],
    ...extra,
  }
}

const ok = (data: unknown, status = 200) =>
  Promise.resolve({ data, response: new Response(null, { status }) })
const failed = (status: number, error: unknown) =>
  Promise.resolve({ error, response: new Response(null, { status }) })

type Reply = Promise<{ data?: unknown; error?: unknown; response: Response }>

interface Options {
  load?: () => Reply
  standard?: () => Reply
  fields?: () => Reply
  put?: (body: unknown) => Reply
  editable?: boolean
}

/** Lets a test change what the page around the panel passes, like a refresh of the document. */
let setShown: ((shown: { version: DocumentVersionView; editable: boolean }) => void) | undefined
let unsaved: (string | undefined)[] = []

function Page({ editable }: { editable: boolean }) {
  const [shown, setState] = useState({ version: version(), editable })
  useEffect(() => {
    setShown = setState
  }, [])
  return (
    <>
      {/* The route of this test, unknown to the types of the app's router. */}
      <Link to={'/other' as '/'}>Leave</Link>
      <DeviationsPanel
        documentId={documentId}
        version={shown.version}
        editable={shown.editable}
        onUnsavedChange={(section) => {
          unsaved.push(section)
        }}
      />
    </>
  )
}

async function renderPanel({
  load = () => ok(report()),
  standard = () => ok({ sections: [parties, liability()] }),
  fields = () => ok({ fields: [], references: [] }),
  put = () => ok(version({ workingState: 3 })),
  editable = true,
}: Options = {}) {
  const get = vi.fn((path: string) => {
    switch (path) {
      case '/api/v1/documents/{id}/versions/{versionId}/deviations':
        return load()
      case '/api/v1/documents/{id}/versions/{versionId}/standard-text':
        return standard()
      case '/api/v1/templates/{id}/versions/{version}/fields':
        return fields()
      default:
        return ok([])
    }
  })
  const putFn = vi.fn((_path: string, init: { body: unknown }) => put(init.body))
  const client = { GET: get, PUT: putFn, POST: vi.fn() } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  const rootRoute = createRootRoute({ component: Outlet })
  const pageRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <Page editable={editable} />,
  })
  const otherRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/other',
    component: () => <p>Other page</p>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([pageRoute, otherRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  await screen.findAllByTestId('locked-section')
  return { router, get, put: putFn, queryClient, user: userEvent.setup() }
}

const row = (title: string) => {
  const found = screen
    .getAllByTestId('locked-section')
    .find((element) => within(element).queryByText(title))
  if (!found) throw new Error(`no row for ${title}`)
  return found
}

const editButton = () => screen.getByRole('button', { name: 'Edit text: Liability' })

async function openEditor(user: UserEvent) {
  await user.click(editButton())
  return screen.findByRole('textbox', { name: 'Paragraph text' })
}

async function typeAtEnd(user: UserEvent, text: HTMLElement, value: string) {
  await user.click(text)
  document.getSelection()?.collapse(text, text.childNodes.length)
  document.dispatchEvent(new Event('selectionchange'))
  await user.keyboard(value)
}

const saveButton = () => screen.getByRole('button', { name: 'Save text' })

beforeEach(() => {
  setLocale('en')
  unsaved = []
})

afterEach(() => {
  setLocale('en')
  setShown = undefined
})

describe('Text of a locked section', () => {
  it('can be edited with an active request only', async () => {
    await renderPanel()
    expect(editButton()).toBeInTheDocument()
    expect(
      within(row('Parties')).queryByRole('button', { name: /Edit text/ }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit text: Terms' })).not.toBeInTheDocument()
  })

  it('cannot be edited on a version that is no draft', async () => {
    await renderPanel({ editable: false })
    expect(screen.queryByRole('button', { name: /Edit text/ })).not.toBeInTheDocument()
  })

  it('tells that the request alone already gives the step, until the text changed', async () => {
    await renderPanel()
    expect(within(row('Liability')).getByText('Deviation requested')).toBeInTheDocument()
    expect(within(row('Liability')).getByText(/request alone gives/)).toBeInTheDocument()
  })

  it('shows the state once the text deviates', async () => {
    await renderPanel({
      load: () =>
        ok(
          report({
            deviations: [
              {
                section: 'liability',
                path: [{ type: 'Block', index: 0 }],
                token: 2,
                location: 'liability › block 0 › word 2',
                change: { type: 'TextReplaced', from: 'limited', to: 'capped' },
                authorized: true,
                reasons: ['The customer insists on a cap'],
              },
            ],
          }),
        ),
    })
    expect(within(row('Liability')).getByText('Text changed')).toBeInTheDocument()
    expect(within(row('Liability')).queryByText(/request alone gives/)).not.toBeInTheDocument()
    expect(screen.getByTestId('deviation-report')).toHaveTextContent('capped')
  })

  it('saves only this section, with the state it was edited on, and nothing else of the draft', async () => {
    const { put, queryClient, user } = await renderPanel()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const text = await openEditor(user)
    expect(saveButton()).toBeDisabled()

    await typeAtEnd(user, text, ' Except for gross negligence.')
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    expect(unsaved.at(-1)).toBe('Liability')
    await user.click(saveButton())

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Save text' })).not.toBeInTheDocument()
    })
    expect(put).toHaveBeenCalledOnce()
    expect(put.mock.calls[0]?.[0]).toBe('/api/v1/documents/{id}/draft')
    expect(put.mock.calls[0]?.[1].body).toEqual({
      content: {
        sections: [parties, liability('Liability is limited. Except for gross negligence.'), terms],
        computed: [],
        references: [],
      },
      expectedWorkingState: 2,
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['documents', documentId] })
    expect(unsaved.at(-1)).toBeUndefined()
  })

  it('works by keyboard: focus enters the editor and comes back to the button', async () => {
    const { user } = await renderPanel()
    editButton().focus()
    await user.keyboard('{Enter}')
    const heading = await screen.findByRole('heading', { name: 'Edit the text of Liability' })
    await waitFor(() => {
      expect(heading).toHaveFocus()
    })

    const close = screen.getByRole('button', { name: 'Close' })
    close.focus()
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(editButton()).toHaveFocus()
    })
  })

  it('keeps the text when the server refuses it, and says why', async () => {
    let refuse = true
    const { put, user } = await renderPanel({
      put: () =>
        refuse
          ? failed(422, { code: 'invalid', message: 'Unknown placeholder.' })
          : ok(version({ workingState: 3 })),
    })
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')
    await user.click(saveButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The text was not saved: The request could not be processed. Check the entered values. Your text is still here.',
    )
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent('Capped.')

    refuse = false
    await user.click(saveButton())
    await waitFor(() => {
      expect(put).toHaveBeenCalledTimes(2)
    })
  })

  it('keeps the text on a network failure', async () => {
    const { user } = await renderPanel({
      put: () => failed(500, { code: 'internal', message: 'Something broke.' }),
    })
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')
    await user.click(saveButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('Something broke.')
    expect(saveButton()).toBeEnabled()
  })

  it('on a concurrent save of something else it saves again on the new state', async () => {
    let conflict = true
    const { put, user } = await renderPanel({
      put: () =>
        conflict
          ? failed(409, { code: 'conflict', message: 'The draft was saved in the meantime.' })
          : ok(version({ workingState: 4 })),
    })
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')
    await user.click(saveButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'the state has changed. The page has been updated; your text is still here.',
    )
    // The refresh brings the new working state; this section is as it was.
    act(() => {
      setShown?.({ version: version({ workingState: 3 }), editable: true })
    })
    conflict = false
    await user.click(saveButton())
    await waitFor(() => {
      expect(put).toHaveBeenCalledTimes(2)
    })
    expect(put.mock.calls[1]?.[1].body).toMatchObject({ expectedWorkingState: 3 })
  })

  it('on a concurrent change of this section the author decides, without losing either text', async () => {
    const { put, user } = await renderPanel()
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Mine.')

    const theirs = version({
      workingState: 3,
      content: { sections: [parties, liability('Theirs.'), terms] },
    })
    act(() => {
      setShown?.({ version: theirs, editable: true })
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else changed this section')
    expect(saveButton()).toBeDisabled()
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent('Mine.')

    await user.click(screen.getByRole('button', { name: 'Keep my text' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await user.click(saveButton())
    await waitFor(() => {
      expect(put).toHaveBeenCalledOnce()
    })
    expect(put.mock.calls[0]?.[1].body).toMatchObject({
      content: { sections: [parties, liability('Liability is limited. Mine.'), terms] },
      expectedWorkingState: 3,
    })
  })

  it('can load the text someone else saved instead', async () => {
    const { user } = await renderPanel()
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Mine.')
    act(() => {
      setShown?.({
        version: version({
          workingState: 3,
          content: { sections: [parties, liability('Theirs.'), terms] },
        }),
        editable: true,
      })
    })
    await user.click(await screen.findByRole('button', { name: 'Load saved text' }))
    expect(await screen.findByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent(
      'Theirs.',
    )
    expect(saveButton()).toBeDisabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('follows a refresh while nothing was typed', async () => {
    const { user } = await renderPanel()
    await openEditor(user)
    act(() => {
      setShown?.({
        version: version({
          workingState: 3,
          content: { sections: [parties, liability('Theirs.'), terms] },
        }),
        editable: true,
      })
    })
    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent('Theirs.')
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps the text but refuses to save once the request is gone', async () => {
    let current = report()
    const { queryClient, user } = await renderPanel({ load: () => ok(current) })
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')

    current = report({ requests: [{ ...request, active: false }], requiresExtraReview: false })
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('no longer has an active request')
    expect(saveButton()).toBeDisabled()
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent('Capped.')
  })

  it('keeps the text but refuses to save once the version left Draft', async () => {
    const { user } = await renderPanel()
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')
    act(() => {
      setShown?.({ version: version({ status: 'InReview' }), editable: false })
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('no longer a draft')
    expect(saveButton()).toBeDisabled()
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent('Capped.')
  })

  it('closes by itself after a submission with nothing unsaved', async () => {
    const { user } = await renderPanel()
    await openEditor(user)
    act(() => {
      setShown?.({ version: version({ status: 'InReview' }), editable: false })
    })
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Save text' })).not.toBeInTheDocument()
    })
  })

  it('asks before leaving with unsaved text', async () => {
    const { router, user } = await renderPanel()
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')

    await user.click(screen.getByRole('link', { name: 'Leave' }))
    const dialog = await screen.findByRole('dialog', { name: 'Leave without saving?' })
    await user.click(within(dialog).getByRole('button', { name: 'Stay on the page' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(router.state.location.pathname).toBe('/')
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent('Capped.')

    await user.click(screen.getByRole('link', { name: 'Leave' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Discard and leave' }),
    )
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/other')
    })
  })

  it('discarding restores the saved text, and nothing blocks leaving', async () => {
    const { router, user } = await renderPanel()
    const text = await openEditor(user)
    await typeAtEnd(user, text, ' Capped.')
    await user.click(screen.getByRole('button', { name: 'Discard changes' }))

    expect(await screen.findByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent(
      'Liability is limited.',
    )
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).not.toHaveTextContent('Capped.')
    await user.click(screen.getByRole('link', { name: 'Leave' }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/other')
    })
  })

  it('restores the standard text exactly, as a change to save', async () => {
    const { put, user } = await renderPanel({
      standard: () =>
        ok({
          sections: [
            parties,
            { ...liability(), blocks: [paragraph('Liability '), paragraph('is limited.')] },
          ],
        }),
    })
    await openEditor(user)
    const restore = screen.getByRole('button', { name: 'Restore standard text' })
    await waitFor(() => {
      expect(restore).toBeEnabled()
    })
    await user.click(restore)
    await waitFor(() => {
      expect(screen.getAllByRole('textbox', { name: 'Paragraph text' })).toHaveLength(2)
    })
    await user.click(saveButton())
    await waitFor(() => {
      expect(put).toHaveBeenCalledOnce()
    })
    expect(put.mock.calls[0]?.[1].body).toMatchObject({
      content: {
        sections: [
          parties,
          { id: 'liability', blocks: [paragraph('Liability '), paragraph('is limited.')] },
          terms,
        ],
      },
    })
  })

  it('still edits when the standard text cannot be loaded, and says what is missing', async () => {
    const { user } = await renderPanel({
      standard: () => failed(500, { code: 'internal', message: 'Something broke.' }),
    })
    await openEditor(user)
    expect(await screen.findByText(/could not be loaded/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restore standard text' })).toBeDisabled()
  })

  it('shows a loading state until the fields are there, and works without them', async () => {
    let answer: (value: Awaited<Reply>) => void = () => undefined
    const { user } = await renderPanel({
      fields: () =>
        new Promise((resolve) => {
          answer = resolve
        }),
    })
    await user.click(editButton())
    expect(await screen.findByText('Loading the editor…')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Paragraph text' })).not.toBeInTheDocument()

    await act(async () => {
      answer(await failed(404, { code: 'not_found', message: 'Gone.' }))
    })
    expect(await screen.findByRole('textbox', { name: 'Paragraph text' })).toBeInTheDocument()
    expect(screen.getByText(/fields/i)).toBeInTheDocument()
  })

  it('speaks German', async () => {
    setLocale('de')
    await renderPanel()
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: 'Text bearbeiten: Liability' }))
    expect(
      await screen.findByRole('heading', { name: 'Text von Liability bearbeiten' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Text speichern' })).toBeDisabled()
    expect(
      screen.getByRole('button', { name: 'Standardtext wiederherstellen' }),
    ).toBeInTheDocument()
  })
})
