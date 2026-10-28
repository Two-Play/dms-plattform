import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Partner } from '@/api/partners'
import type { MetadataValue, TemplateFields } from '@/api/templates'
import { NewDocumentPage } from './new-document-page'

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'

const fields: TemplateFields = {
  templateId,
  version: 1,
  name: 'Service contract',
  documentClassId: '7c1e2f0a-0000-4000-8000-000000000002',
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
    {
      key: 'contractPartner',
      label: 'Contract partner',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: true,
      reference: { reference: 'contractPartner', attribute: 'name' },
    },
    {
      key: 'partnerAddress',
      label: 'Partner address',
      fieldType: { type: 'Text' },
      multiple: false,
      required: false,
      usedInTemplate: true,
      reference: { reference: 'contractPartner', attribute: 'address' },
    },
  ],
  references: [
    {
      key: 'contractPartner',
      label: 'Contract partner',
      source: 'partner-master',
      fields: [
        { attribute: 'id', field: 'partnerId' },
        { attribute: 'name', field: 'contractPartner' },
        { attribute: 'address', field: 'partnerAddress' },
      ],
    },
  ],
}

const nordlicht: Partner = {
  id: 'P-10001',
  name: 'Nordlicht Logistik GmbH',
  street: 'Hafenstraße 12',
  postalCode: '20457',
  city: 'Hamburg',
  country: 'DE',
  address: 'Hafenstraße 12, 20457 Hamburg, DE',
}

const ok = (data: unknown, status = 200) =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, code: string, message: string) =>
  Promise.resolve({
    data: undefined,
    error: { code, message },
    response: new Response(null, { status }),
  })

const unavailable =
  'The partner master is not reachable: the partner master did not answer within 3000 ms. You can save the draft without it and pick the partner later.'

function version(label: string) {
  return { id: 'v1', version: label }
}

function setup({ partnerMasterDown = false } = {}) {
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/templates') {
      return ok([
        {
          templateId,
          version: 1,
          name: 'Service contract',
          documentClassId: fields.documentClassId,
          documentClassVersion: 1,
        },
      ])
    }
    if (path === '/api/v1/partners') {
      return partnerMasterDown
        ? failed(503, 'partner_directory_unavailable', unavailable)
        : ok([nordlicht])
    }
    return ok(fields)
  })
  const post = vi.fn<(path: string, init: { body: unknown }) => Promise<unknown>>((path) => {
    if (path === '/api/v1/documents') {
      return ok({ id: documentId, current: version('1.0-draft.1') }, 201)
    }
    return ok({ html: '<article class="dms-document"></article>', missingRequired: [], issues: [] })
  })
  const put = vi.fn<(path: string, init: unknown) => Promise<unknown>>((path) =>
    path.endsWith('/references/{key}') && partnerMasterDown
      ? failed(503, 'partner_directory_unavailable', unavailable)
      : ok(version('1.0-draft.2')),
  )
  const client = { GET: get, POST: post, PUT: put, DELETE: vi.fn() } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <NewDocumentPage />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  const previewValues = (): Record<string, MetadataValue> | undefined => {
    const call = post.mock.calls.filter(([p]) => p.endsWith('/preview')).at(-1)
    return (call?.[1].body as { values: Record<string, MetadataValue> } | undefined)?.values
  }
  return { get, post, put, previewValues }
}

async function chooseTemplate() {
  const user = userEvent.setup()
  const select = await screen.findByLabelText('Template')
  await waitFor(() => {
    expect(select).toBeEnabled()
  })
  await user.selectOptions(select, 'Service contract (version 1)')
  await screen.findByRole('group', { name: 'From the partner master' })
  return user
}

describe('Partner master on the new document page', () => {
  it('searches the partner master and shows the taken-over values read-only', async () => {
    const { get, previewValues } = setup()
    const user = await chooseTemplate()

    const partnerField = screen.getByLabelText(/^Contract partner\*?$/, {
      selector: 'input[readonly]',
    })
    expect(partnerField).toHaveAttribute('aria-readonly', 'true')
    expect(screen.getAllByText(/From partner master/).length).toBe(2)

    await user.type(screen.getByRole('searchbox'), 'nord')
    const hits = await screen.findByRole('list', { name: 'Contract partner hits' })
    expect(get).toHaveBeenCalledWith(
      '/api/v1/partners',
      expect.objectContaining({
        params: { query: { q: 'nord', limit: 10 } },
      }),
    )
    await user.click(within(hits).getByRole('button', { name: /Nordlicht Logistik GmbH/ }))

    expect(partnerField).toHaveValue('Nordlicht Logistik GmbH')
    expect(screen.getByLabelText('Partner address', { selector: 'input' })).toHaveValue(
      'Hafenstraße 12, 20457 Hamburg, DE',
    )
    expect(screen.getByText('(P-10001)')).toBeInTheDocument()
    await waitFor(() => {
      expect(previewValues()?.contractPartner).toEqual({
        type: 'Text',
        value: 'Nordlicht Logistik GmbH',
      })
    })

    await user.click(screen.getByRole('button', { name: 'Remove partner' }))
    expect(partnerField).toHaveValue('')
  })

  it('saves the draft without the taken-over values, then takes the partner over on the server', async () => {
    const { post, put } = setup()
    const user = await chooseTemplate()
    await user.type(screen.getByLabelText(/^Title\*?$/), 'Service contract Nordlicht')
    await user.type(screen.getByRole('searchbox'), 'nord')
    await user.click(await screen.findByRole('button', { name: /Nordlicht Logistik GmbH/ }))

    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('Draft saved as version 1.0-draft.2.')).toBeInTheDocument()
    const create = post.mock.calls.find(([p]) => p === '/api/v1/documents')
    expect(create?.[1].body).toEqual({
      templateId,
      templateVersion: 1,
      title: 'Service contract Nordlicht',
      values: { title: { type: 'Text', value: 'Service contract Nordlicht' } },
    })
    expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/references/{key}', {
      params: { path: { id: documentId, key: 'contractPartner' } },
      body: { entryId: 'P-10001' },
    })
  })

  it('tells the author when the partner master is down and still saves the draft', async () => {
    const { post } = setup({ partnerMasterDown: true })
    const user = await chooseTemplate()

    await user.type(screen.getByRole('searchbox'), 'nord')
    const alert = await screen.findByText(unavailable)
    expect(alert).toHaveAttribute('role', 'alert')

    await user.type(screen.getByLabelText(/^Title\*?$/), 'Contract')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('Draft saved as version 1.0-draft.1.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/documents', expect.anything())
  })

  it('asks for a title before saving', async () => {
    const { post } = setup()
    const user = await chooseTemplate()
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText('Enter a title before saving the draft.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalledWith('/api/v1/documents', expect.anything())
  })
})
