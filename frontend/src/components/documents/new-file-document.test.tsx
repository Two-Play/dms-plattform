import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { TemplateFields } from '@/api/templates'
import { NewDocumentPage } from './new-document-page'

const structured = '7c1e2f0a-0000-4000-8000-000000000021'
const wordTemplate = '7c1e2f0a-0000-4000-8000-000000000022'
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d3'

const fields: TemplateFields = {
  templateId: structured,
  version: 1,
  name: 'Policy',
  documentClassId: '7c1e2f0a-0000-4000-8000-000000000023',
  documentClassVersion: 1,
  fields: [
    {
      key: 'title',
      label: 'Title',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: false,
    },
  ],
  references: [],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

function setup() {
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/templates') {
      return ok([
        {
          templateId: structured,
          version: 1,
          name: 'Policy',
          documentClassId: 'c',
          documentClassVersion: 1,
        },
        {
          templateId: wordTemplate,
          version: 2,
          name: 'Wartungsvertrag',
          documentClassId: 'c',
          documentClassVersion: 1,
        },
      ])
    }
    if (path === '/api/v1/word-templates') {
      return ok([{ templateId: wordTemplate, version: 2, status: 'Released', fields: [] }])
    }
    return ok(fields)
  })
  const created = { id: documentId, current: { version: '1.0-draft.1' } }
  const post = vi.fn(() => ok(created, 201))
  const client = { GET: get, POST: post } as unknown as ApiClient
  const onCreated = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <NewDocumentPage onCreated={onCreated} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, onCreated, user: userEvent.setup() }
}

describe('New file and Word documents (DMS-115)', () => {
  it('a Word template creates the document from its Word file and continues on the document page', async () => {
    const { post, onCreated, user } = setup()
    const select = await screen.findByLabelText('Template')
    await screen.findByRole('option', { name: 'Wartungsvertrag (Word) – version 2' })
    await user.selectOptions(select, `${wordTemplate}:2`)
    // no choice of content and no live preview for a Word template
    expect(
      screen.queryByRole('radio', { name: 'Upload a file after saving' }),
    ).not.toBeInTheDocument()
    expect(await screen.findByText(/starts with the Word template’s file/)).toBeInTheDocument()
    await user.type(await screen.findByLabelText(/Title/), 'Wartungsvertrag ACME')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents/word', {
        body: {
          templateId: wordTemplate,
          templateVersion: 2,
          title: 'Wartungsvertrag ACME',
          values: { title: { type: 'Text', value: 'Wartungsvertrag ACME' } },
        },
      })
    })
    expect(onCreated).toHaveBeenCalledWith(documentId)
  })

  it('a structured template can be used for a file document uploaded after saving', async () => {
    const { post, onCreated, user } = setup()
    const select = await screen.findByLabelText('Template')
    await screen.findByRole('option', { name: /Policy/ })
    await user.selectOptions(select, `${structured}:1`)
    await user.click(screen.getByRole('radio', { name: 'Upload a file after saving' }))
    await user.type(await screen.findByLabelText(/Title/), 'Plan')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents', {
        body: expect.objectContaining({
          templateId: structured,
          title: 'Plan',
          kind: 'File',
        }) as unknown,
      })
    })
    expect(onCreated).toHaveBeenCalledWith(documentId)
  })
})
