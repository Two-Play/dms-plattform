import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { TemplateVersion } from '@/api/template-admin'
import type { UploadFile, WordTemplate } from '@/api/word'
import { createAppRouter } from '@/router'
import { WordTemplatePanel } from './word-template-panel'

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

const templateId = '7c1e2f0a-0000-4000-8000-000000000011'
const classId = '7c1e2f0a-0000-4000-8000-000000000012'

function version(status: string, over: Partial<TemplateVersion> = {}): TemplateVersion {
  return {
    templateId,
    version: 1,
    name: 'Wartungsvertrag',
    documentClassId: classId,
    documentClassVersion: 3,
    status,
    content: { sections: [] },
    createdBy: 'designer-1',
    createdAt: '2026-10-20T10:00:00Z',
    editedBy: [],
    ...over,
  }
}

const word: WordTemplate = {
  templateId,
  version: 1,
  status: 'Draft',
  templateName: 'Wartungsvertrag',
  name: 'Wartungsvertrag.docx',
  size: 12_345,
  sha256: 'cd'.repeat(32),
  scanStatus: 'NotScanned',
  fields: [
    {
      key: 'documentNumber',
      label: 'Dokumentnummer',
      type: 'Text',
      required: false,
      source: 'Automatic',
    },
    { key: 'customer', label: 'customer', type: 'Text', required: false, source: 'Manual' },
  ],
  uploadedBy: 'designer-1',
  uploadedAt: '2026-10-29T09:00:00Z',
  downloadUrl: `/api/v1/admin/templates/${templateId}/versions/1/word/file`,
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

function renderPanel(v: TemplateVersion, upload?: UploadFile<WordTemplate>) {
  const put = vi.fn((_path: string, init: { body: { fields: WordTemplate['fields'] } }) =>
    ok({ ...word, fields: init.body.fields }),
  )
  const get = vi.fn((path: string) =>
    path === '/api/v1/users'
      ? ok([{ id: 'designer-1', name: 'Dora Designer' }])
      : ok(new Blob(['x'])),
  )
  const client = { GET: get, PUT: put } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <WordTemplatePanel version={v} word={word} upload={upload} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { put, user: userEvent.setup({ applyAccept: false }) }
}

describe('Word templates (DMS-115)', () => {
  it('a designer edits the field definitions of a draft and saves them', async () => {
    const { put, user } = renderPanel(version('Draft'))

    expect(screen.getByText('Wartungsvertrag.docx')).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Field definitions' })
    expect(within(table).getByText('documentNumber')).toBeInTheDocument()
    const label = screen.getByRole('textbox', { name: 'Label: customer' })
    await user.clear(label)
    await user.type(label, 'Kunde')
    await user.click(screen.getByRole('checkbox', { name: 'Required: customer' }))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Type: customer' }), 'Date')
    await user.click(screen.getByRole('button', { name: 'Save field definitions' }))

    await waitFor(() => {
      expect(put).toHaveBeenCalledWith(
        '/api/v1/admin/templates/{id}/versions/{version}/word/fields',
        {
          params: { path: { id: templateId, version: 1 } },
          body: {
            fields: [
              word.fields?.[0],
              { key: 'customer', label: 'Kunde', type: 'Date', required: true, source: 'Manual' },
            ],
          },
        },
      )
    })
    expect(screen.getByText('How to mark fields in Word')).toBeInTheDocument()
  })

  it('a released Word template is read-only', () => {
    renderPanel(version('Released'))
    expect(screen.queryByRole('button', { name: 'Save field definitions' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Label: customer' })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Required: customer' })).toBeDisabled()
    expect(screen.getByText('The Word file is frozen with the version.')).toBeInTheDocument()
  })

  it('replacing the Word file uploads it and shows the fields found', async () => {
    const upload = vi.fn<UploadFile<WordTemplate>>(() =>
      Promise.resolve({
        ...word,
        fields: [
          { key: 'project', label: 'Projekt', type: 'Text', required: false, source: 'Manual' },
        ],
      }),
    )
    const { user } = renderPanel(version('Draft'), upload)
    const input = screen.getByLabelText('Replace Word file…', { selector: 'input' })

    await user.upload(input, new File(['x'], 'notes.txt'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose a Word document (.docx).')
    expect(upload).not.toHaveBeenCalled()

    await user.upload(input, new File(['x'], 'Vertrag v2.docx'))
    expect(await screen.findByText('project')).toBeInTheDocument()
    expect(upload).toHaveBeenCalledTimes(1)
  })

  it('the template list marks Word templates and creates a new one for a chosen class (DMS-121)', async () => {
    const created = version('Draft', { templateId: 'new-1', name: 'Angebot' })
    const get = vi.fn((p: string) => {
      switch (p) {
        case '/api/v1/me':
          return ok({ userId: 'designer-1', tenantId: 'tenant-1', roles: ['TemplateDesigner'] })
        case '/api/v1/admin/templates':
          return ok([
            version('Released'),
            version('Released', { templateId: 'struct-1', name: 'Policy' }),
          ])
        case '/api/v1/admin/word-templates':
          return ok([word])
        case '/api/v1/admin/template-classes':
          return ok([
            {
              id: 'class-root',
              key: 'contract',
              version: 2,
              label: 'Contract',
              path: ['Contract'],
            },
            {
              id: 'class-offer',
              key: 'offer',
              version: 5,
              label: 'Offer',
              path: ['Contract', 'Offer'],
            },
          ])
        default:
          return ok([])
      }
    })
    const post = vi.fn(() => ok(created, 201))
    const client = { GET: get, POST: post } as unknown as ApiClient
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const router = createAppRouter(
      queryClient,
      createMemoryHistory({ initialEntries: ['/templates'] }),
    )
    render(
      <QueryClientProvider client={queryClient}>
        <ApiClientProvider client={client}>
          <RouterProvider router={router} context={{ auth: signedIn }} />
        </ApiClientProvider>
      </QueryClientProvider>,
    )
    const user = userEvent.setup({ applyAccept: false })

    const table = await screen.findByRole('table', { name: 'Templates' })
    const wordRow = within(table).getByRole('link', { name: 'Wartungsvertrag' }).closest('tr')
    const policyRow = within(table).getByRole('link', { name: 'Policy' }).closest('tr')
    expect(wordRow && within(wordRow).getByText('Word')).toBeInTheDocument()
    expect(policyRow && within(policyRow).queryByText('Word')).toBeNull()

    expect(screen.getByText('New Word template')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Name'), 'Angebot')
    const classes = screen.getByLabelText('Document class')
    expect(
      await within(classes).findByRole('option', { name: 'Contract › Offer' }),
    ).toBeInTheDocument()
    await user.selectOptions(classes, 'class-offer')
    // The upload itself needs the access token; the template is created first.
    await user.upload(screen.getByLabelText('Word file (.docx)'), new File(['x'], 'Angebot.docx'))
    await user.click(screen.getByRole('button', { name: 'Create Word template' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/admin/templates', {
        body: {
          name: 'Angebot',
          description: undefined,
          documentClassId: 'class-offer',
          documentClassVersion: 5,
          content: { sections: [] },
        },
      })
    })
  })
})
