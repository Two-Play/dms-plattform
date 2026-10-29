import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { ContentIssue, TemplateContent, TemplateVersion } from '@/api/template-admin'
import type { TemplateFields } from '@/api/templates'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'designer-1', name: 'Dora Designer' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

beforeAll(() => {
  // jsdom has no layout; ProseMirror asks for coordinates when it maps the caret while typing.
  const none: DOMRect[] = []
  Range.prototype.getBoundingClientRect = () => new DOMRect()
  Range.prototype.getClientRects = () => Object.assign(none, { item: () => null })
  document.elementFromPoint = () => null
})

const templateId = '7c1e2f0a-0000-4000-8000-000000000011'
const classId = '7c1e2f0a-0000-4000-8000-000000000012'

const content: TemplateContent = {
  sections: [
    {
      id: 'intro',
      title: 'Introduction',
      locked: false,
      blocks: [
        {
          type: 'Paragraph',
          content: [
            { type: 'Text', value: 'Term: ', marks: [] },
            { type: 'Placeholder', path: 'termType' },
          ],
        },
      ],
    },
    {
      id: 'liability',
      title: 'Liability',
      locked: true,
      blocks: [{ type: 'ClauseRef', clauseKey: 'liability', version: 2 }],
    },
  ],
  computed: [],
  references: [],
}

function version(n: number, status: string): TemplateVersion {
  return {
    templateId,
    version: n,
    name: 'Service contract',
    description: 'Standard service contract',
    documentClassId: classId,
    documentClassVersion: 1,
    status,
    content,
    createdBy: 'designer-1',
    createdAt: '2026-10-20T10:00:00Z',
    editedBy: ['designer-1'],
  }
}

const fields: TemplateFields = {
  templateId,
  version: 2,
  name: 'Service contract',
  documentClassId: classId,
  documentClassVersion: 1,
  fields: [
    {
      key: 'termType',
      label: 'Term',
      fieldType: {
        type: 'Selection',
        options: [
          { code: 'fixed', label: 'Fixed term', active: true },
          { code: 'open', label: 'Open-ended', active: true },
        ],
      },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
    {
      key: 'termMonths',
      label: 'Term in months',
      fieldType: { type: 'Number' },
      multiple: false,
      required: false,
      usedInTemplate: false,
    },
    {
      key: 'monthlyFee',
      label: 'Monthly fee',
      fieldType: { type: 'Amount' },
      multiple: false,
      required: false,
      usedInTemplate: false,
    },
  ],
  references: [],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

let versions: TemplateVersion[]
let checkIssues: ContentIssue[]

beforeEach(() => {
  versions = [version(1, 'Released'), version(2, 'Draft')]
  checkIssues = []
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'designer-1', tenantId: 'tenant-1', roles: ['TemplateDesigner'] })
      case '/api/v1/admin/templates':
        return ok([versions.at(-1)])
      case '/api/v1/admin/templates/{id}':
        return ok(versions)
      case '/api/v1/admin/templates/{id}/versions/{version}/fields':
        return ok(fields)
      default:
        return failed(404, { code: 'not_found', message: `unexpected GET ${p}` })
    }
  })
  const post = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/admin/templates/check':
        return ok({ valid: checkIssues.length === 0, issues: checkIssues })
      default:
        return failed(404, { code: 'not_found', message: `unexpected POST ${p}` })
    }
  })
  const put = vi.fn((_p: string, init: { body: { content: TemplateContent } }) => {
    versions = versions.map((v) => (v.version === 2 ? { ...v, content: init.body.content } : v))
    return ok(versions.at(-1))
  })
  const client = { GET: get, POST: post, PUT: put } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, put, post, user: userEvent.setup() }
}

/** The content sent with the last save. */
function savedContent(put: ReturnType<typeof renderAt>['put']): TemplateContent {
  const call = put.mock.calls.at(-1)
  if (!call) throw new Error('not saved')
  return call[1].body.content
}

function sectionTitles(): string[] {
  return screen.getAllByRole('heading', { level: 4 }).map((h) => h.textContent)
}

/** The breadcrumb link back to the template list. */
function templatesLink(): HTMLElement {
  const link = screen.getAllByRole('link', { name: 'Templates' })[0]
  if (!link) throw new Error('no link to the templates')
  return link
}

async function openDraft() {
  const r = renderAt(`/templates/${templateId}`)
  await screen.findByRole('heading', { level: 4, name: /Introduction/ })
  // The field list loads separately; wait for it so the pickers offer the class fields.
  await screen.findByRole('table', { name: 'Fields' })
  return r
}

describe('Visual template editor', () => {
  it('shows the content visually and keeps the JSON in a hidden advanced view', async () => {
    const { user } = await openDraft()

    expect(sectionTitles()).toEqual(['Section “Introduction”', 'Section “Liability”'])
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveTextContent(
      'Term: {{termType}}',
    )
    expect(screen.getByLabelText('Clause key')).toHaveValue('liability')
    const locks = screen.getAllByRole('checkbox', { name: 'Locked (standard text)' })
    expect(locks.map((c) => (c as HTMLInputElement).checked)).toEqual([false, true])
    expect(screen.queryByLabelText('Content (JSON)')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Advanced: show JSON' }))
    expect(screen.getByLabelText('Content (JSON)')).toHaveValue(JSON.stringify(content, null, 2))
  })

  it('adds, renames, reorders with keyboard and mouse, and removes sections', async () => {
    const { put, user } = await openDraft()

    await user.click(screen.getByRole('button', { name: 'Add section' }))
    const newTitle = screen.getAllByRole('textbox', { name: 'Title' })[2]
    if (!newTitle) throw new Error('no title')
    await waitFor(() => {
      expect(document.activeElement).toBe(newTitle)
    })
    await user.clear(newTitle)
    await user.type(newTitle, 'Payment')
    expect(sectionTitles()).toEqual([
      'Section “Introduction”',
      'Section “Liability”',
      'Section “Payment”',
    ])

    // Keyboard: the move handle moves with the arrow keys and keeps the focus.
    screen.getByRole('button', { name: 'Move Section “Payment”' }).focus()
    await user.keyboard('{ArrowUp}')
    expect(sectionTitles()).toEqual([
      'Section “Introduction”',
      'Section “Payment”',
      'Section “Liability”',
    ])
    await waitFor(() => {
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Move Section “Payment”' }),
      )
    })
    expect(screen.getByText('Section “Payment” moved to position 2 of 3.')).toBeInTheDocument()
    await user.keyboard('{Home}')
    expect(sectionTitles()[0]).toBe('Section “Payment”')

    // Buttons.
    await user.click(screen.getByRole('button', { name: 'Move Section “Payment” down' }))
    expect(sectionTitles()[1]).toBe('Section “Payment”')

    // Mouse: drag the handle of "Liability" onto the first section.
    const liability = screen.getByRole('button', { name: 'Move Section “Liability”' })
    const first = screen.getByRole('region', { name: 'Section “Introduction”' })
    const dataTransfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' }
    fireEvent.dragStart(liability, { dataTransfer })
    fireEvent.dragOver(first, { dataTransfer })
    fireEvent.drop(first, { dataTransfer })
    expect(sectionTitles()).toEqual([
      'Section “Liability”',
      'Section “Introduction”',
      'Section “Payment”',
    ])

    // Removing a section with content asks first.
    await user.click(screen.getByRole('button', { name: 'Remove Section “Payment”' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Remove section' }))
    expect(sectionTitles()).toEqual(['Section “Liability”', 'Section “Introduction”'])

    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await screen.findByText('Draft saved.')
    expect(savedContent(put).sections?.map((s) => s.id)).toEqual(['liability', 'intro'])
  })

  it('formats text and inserts a field from the class into a paragraph', async () => {
    const { put, user } = await openDraft()
    const intro = screen.getByRole('region', { name: 'Section “Introduction”' })

    await user.selectOptions(within(intro).getByLabelText('Type of the new block'), 'Paragraph')
    await user.click(within(intro).getByRole('button', { name: 'Add block' }))
    const text = within(intro).getAllByRole('textbox', { name: 'Paragraph text' })[1]
    if (!text) throw new Error('no new paragraph')
    await waitFor(() => {
      expect(document.activeElement).toBe(text)
    })

    const group = text.closest('[role="group"]')
    if (!(group instanceof HTMLElement)) throw new Error('no block')
    await user.click(within(group).getByRole('button', { name: 'Bold (Ctrl+B)' }))
    expect(within(group).getByRole('button', { name: 'Bold (Ctrl+B)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await user.click(within(group).getByRole('button', { name: 'Insert field' }))
    const picker = within(group).getByRole('group', { name: 'Insert a field' })
    await user.selectOptions(within(picker).getByLabelText('Field'), 'monthlyFee')
    expect(
      within(within(picker).getByLabelText('Format'))
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Default', 'plain', 'amount'])
    await user.selectOptions(within(picker).getByLabelText('Format'), 'amount')
    await user.click(within(picker).getByRole('button', { name: 'Insert' }))
    expect(text).toHaveTextContent('{{monthlyFee | amount}}')

    await user.selectOptions(within(group).getByLabelText('Text style'), '2')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await screen.findByText('Draft saved.')
    expect(savedContent(put).sections?.[0]?.blocks?.[1]).toEqual({
      type: 'Heading',
      level: 2,
      content: [{ type: 'Placeholder', path: 'monthlyFee', format: 'amount' }],
    })
  })

  it('builds conditions and computed fields with forms', async () => {
    const { put, user } = await openDraft()
    const intro = screen.getByRole('region', { name: 'Section “Introduction”' })

    await user.selectOptions(within(intro).getByLabelText('Type of the new block'), 'Conditional')
    await user.click(within(intro).getByRole('button', { name: 'Add block' }))
    const condition = within(intro).getByRole('group', { name: 'Show the content if' })
    await user.click(within(condition).getByRole('button', { name: 'Add rule' }))
    await user.selectOptions(within(condition).getByLabelText('Field of rule 1'), 'termType')
    await user.selectOptions(within(condition).getByLabelText('Value of rule 1'), 'fixed')
    await user.click(within(condition).getByRole('button', { name: 'Add rule' }))
    await user.selectOptions(within(condition).getByLabelText('Field of rule 2'), 'termMonths')
    await user.selectOptions(within(condition).getByLabelText('Comparison of rule 2'), 'ge')
    await user.type(within(condition).getByLabelText('Value of rule 2'), 'x')
    expect(within(condition).getByText('Enter a number, e.g. 12 or 12.5.')).toBeInTheDocument()
    await user.clear(within(condition).getByLabelText('Value of rule 2'))
    await user.type(within(condition).getByLabelText('Value of rule 2'), '12')
    expect(
      within(condition).getByText('Expression: termType == "fixed" && termMonths >= 12.0'),
    ).toBeInTheDocument()

    // The same condition as a guided expression, and back.
    await user.click(within(intro).getByRole('button', { name: 'Edit as expression' }))
    const expression = within(intro).getByLabelText('Condition (CEL expression)')
    expect(expression).toHaveValue('termType == "fixed" && termMonths >= 12.0')
    await user.type(expression, ' && (')
    expect(within(intro).getByText('The brackets do not match.')).toBeInTheDocument()
    expect(within(intro).getByRole('button', { name: 'Edit as form' })).toBeDisabled()
    await user.type(expression, '{Backspace}{Backspace}{Backspace}{Backspace}{Backspace}')
    await user.click(within(intro).getByRole('button', { name: 'Edit as form' }))
    expect(within(intro).getByLabelText('Field of rule 2')).toHaveValue('termMonths')

    // A computed field: monthly fee times term.
    await user.click(screen.getByRole('button', { name: 'Add computed field' }))
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'totalValue')
    const calc = screen.getByRole('group', { name: 'Calculation' })
    await user.selectOptions(within(calc).getByLabelText('Value 1'), 'monthlyFee.amount')
    await user.click(within(calc).getByRole('button', { name: 'Add step' }))
    await user.selectOptions(within(calc).getByLabelText('Value 2'), 'termMonths')

    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await screen.findByText('Draft saved.')
    const saved = savedContent(put)
    expect(saved.sections?.[0]?.blocks?.[1]).toMatchObject({
      type: 'Conditional',
      condition: 'termType == "fixed" && termMonths >= 12.0',
    })
    expect(saved.computed).toEqual([
      { key: 'totalValue', expression: 'monthlyFee.amount * termMonths' },
    ])
  })

  it('types text and splits a paragraph with Enter', async () => {
    const { put, user } = await openDraft()
    const text = screen.getByRole('textbox', { name: 'Paragraph text' })

    await user.click(text)
    // Caret after the placeholder, at the end of the text.
    document.getSelection()?.collapse(text, text.childNodes.length)
    document.dispatchEvent(new Event('selectionchange'))
    await user.keyboard(' applies.{Enter}Second')
    const texts = screen.getAllByRole('textbox', { name: 'Paragraph text' })
    expect(texts).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await screen.findByText('Draft saved.')
    const blocks = savedContent(put).sections?.[0]?.blocks ?? []
    expect(blocks).toHaveLength(2)
    expect(blocks[0]).toEqual({
      type: 'Paragraph',
      content: [
        { type: 'Text', value: 'Term: ', marks: [] },
        { type: 'Placeholder', path: 'termType' },
        { type: 'Text', value: ' applies.', marks: [] },
      ],
    })
    expect(blocks[1]).toEqual({
      type: 'Paragraph',
      content: [{ type: 'Text', value: 'Second', marks: [] }],
    })
  })

  it('edits lists, tables, clauses, repeats and reference fields', async () => {
    const { put, user } = await openDraft()
    const liability = screen.getByRole('region', { name: /Section “Liability”/ })
    const add = async (type: string) => {
      await user.selectOptions(
        within(liability).getAllByLabelText('Type of the new block').at(-1) ?? liability,
        type,
      )
      await user.click(
        within(liability).getAllByRole('button', { name: 'Add block' }).at(-1) ?? liability,
      )
    }

    await user.clear(within(liability).getByLabelText('Clause version'))
    await user.type(within(liability).getByLabelText('Clause version'), '3')
    await add('OrderedList')
    await user.click(within(liability).getByRole('button', { name: 'Add item' }))
    await add('Table')
    await user.click(within(liability).getByRole('button', { name: 'Add column' }))
    await user.click(within(liability).getByRole('button', { name: 'Remove row 2' }))
    await add('Repeat')
    const item = within(liability).getByLabelText('Name of the entry')
    await user.clear(item)
    await user.type(item, 'Service')
    expect(within(liability).getByText(/Lower camel case/)).toBeInTheDocument()
    await user.clear(item)
    await user.type(item, 'service')
    await add('PageBreak')

    await user.click(screen.getByRole('button', { name: 'Add reference field' }))
    const refs = screen.getByRole('region', { name: 'Reference fields' })
    await user.type(within(refs).getByLabelText('Name'), 'partner')
    await user.type(within(refs).getByLabelText('Label'), 'Contract partner')
    await user.selectOptions(within(refs).getByLabelText('Field for attribute 1'), 'termType')

    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await screen.findByText('Draft saved.')
    const saved = savedContent(put)
    const blocks = saved.sections?.[1]?.blocks ?? []
    expect(blocks.map((b) => b.type)).toEqual([
      'ClauseRef',
      'OrderedList',
      'Table',
      'Repeat',
      'PageBreak',
    ])
    expect(blocks[0]).toEqual({ type: 'ClauseRef', clauseKey: 'liability', version: 3 })
    const list = blocks[1]
    expect(list?.type === 'OrderedList' ? list.items : []).toHaveLength(2)
    const table = blocks[2]
    expect(table?.type === 'Table' ? table.rows?.map((r) => r.length) : []).toEqual([3])
    expect(blocks[3]).toMatchObject({ type: 'Repeat', itemName: 'service' })
    expect(saved.references).toEqual([
      {
        key: 'partner',
        label: 'Contract partner',
        source: 'partner-master',
        fields: [{ attribute: 'id', field: 'termType' }],
      },
    ])
  })

  it('shows server issues at the affected element', async () => {
    checkIssues = [
      {
        location: {
          sectionId: 'intro',
          blockPath: '/blocks/0',
          part: 'content/1',
          line: 0,
          column: 0,
        },
        message: "placeholder 'termTyp' does not refer to a field in scope",
      },
      {
        location: {
          sectionId: 'liability',
          blockPath: '/blocks/0',
          part: 'version',
          line: 0,
          column: 0,
        },
        message: 'clause versions start at 1',
      },
    ]
    const { user } = await openDraft()

    await user.click(screen.getByRole('button', { name: 'Check' }))

    expect(await screen.findByText('2 issues in the content')).toBeInTheDocument()
    const paragraph = screen.getByRole('textbox', { name: 'Paragraph text' })
    expect(paragraph).toHaveAttribute('aria-invalid', 'true')
    expect(paragraph).toHaveAccessibleDescription(
      "Placeholder 2: placeholder 'termTyp' does not refer to a field in scope",
    )
    const liability = screen.getByRole('region', { name: /Section “Liability”/ })
    expect(within(liability).getByText(/clause versions start at 1/)).toBeInTheDocument()
    expect(within(liability).getByLabelText('Clause version')).toHaveAttribute(
      'aria-invalid',
      'true',
    )

    // "Show" moves the focus to the element.
    const show = screen.getAllByRole('button', { name: 'Show' })[1]
    if (!show) throw new Error('no link')
    await user.click(show)
    await waitFor(() => {
      expect(document.activeElement).toBe(within(liability).getAllByRole('group')[0])
    })
  })

  it('asks before leaving with unsaved changes', async () => {
    const { router, user } = await openDraft()

    const title = screen.getAllByRole('textbox', { name: 'Title' })[0]
    if (!title) throw new Error('no title')
    await user.type(title, ' and scope')
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()

    await user.click(templatesLink())
    const dialog = await screen.findByRole('dialog', { name: 'Leave without saving?' })
    await user.click(within(dialog).getByRole('button', { name: 'Stay on the page' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(router.state.location.pathname).toBe(`/templates/${templateId}`)
    expect(title).toHaveValue('Introduction and scope')

    await user.click(templatesLink())
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Discard and leave' }),
    )
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/templates')
    })
  })

  it('lets changes be discarded, so nothing blocks leaving', async () => {
    const { router, user } = await openDraft()
    const title = screen.getAllByRole('textbox', { name: 'Title' })[0]
    if (!title) throw new Error('no title')
    await user.type(title, '!')
    await user.click(screen.getByRole('button', { name: 'Discard changes' }))
    expect(screen.getAllByRole('textbox', { name: 'Title' })[0]).toHaveValue('Introduction')
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument()

    await user.click(templatesLink())
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/templates')
    })
  })

  it('shows released versions read-only', async () => {
    renderAt(`/templates/${templateId}?version=1`)
    await screen.findByRole('heading', { level: 4, name: /Introduction/ })

    expect(screen.queryByRole('button', { name: 'Add section' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument()
    expect(screen.getAllByRole('textbox', { name: 'Title' })[0]).toHaveAttribute('readonly')
    expect(screen.getByRole('textbox', { name: 'Paragraph text' })).toHaveAttribute(
      'contenteditable',
      'false',
    )
  })

  it('speaks German', async () => {
    setLocale('de')
    const { user } = renderAt(`/templates/${templateId}`)
    await screen.findByRole('heading', { level: 4, name: /Introduction/ })

    expect(screen.getByRole('button', { name: 'Abschnitt hinzufügen' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Text des Absatzes' })).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox', { name: 'Gesperrt (Standardtext)' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Block hinzufügen' })).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Erweitert: JSON anzeigen' }))
    expect(screen.getByLabelText('Inhalt (JSON)')).toBeInTheDocument()
  })
})
