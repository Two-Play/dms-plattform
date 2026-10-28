import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { MetadataValue, Preview, TemplateFields } from '@/api/templates'
import { NewDocumentPage } from './new-document-page'

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'

const fields: TemplateFields = {
  templateId,
  version: 2,
  name: 'Service contract',
  documentClassId: '7c1e2f0a-0000-4000-8000-000000000002',
  documentClassVersion: 1,
  fields: [
    {
      key: 'clientName',
      label: 'Client name',
      fieldType: { type: 'Text', maxLength: 200 },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
    {
      key: 'monthlyFee',
      label: 'Monthly fee',
      fieldType: { type: 'Amount', currencies: ['EUR', 'CHF'] },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
    {
      key: 'termMonths',
      label: 'Term in months',
      fieldType: { type: 'Number', min: 1, max: 120 },
      multiple: false,
      required: false,
      usedInTemplate: true,
    },
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
      required: false,
      usedInTemplate: true,
    },
    {
      key: 'startDate',
      label: 'Start date',
      fieldType: { type: 'Date' },
      multiple: false,
      required: false,
      usedInTemplate: true,
    },
    {
      key: 'international',
      label: 'International',
      fieldType: { type: 'YesNo' },
      multiple: false,
      required: false,
      usedInTemplate: true,
    },
    {
      key: 'tags',
      label: 'Tags',
      fieldType: { type: 'Text' },
      multiple: true,
      required: false,
      usedInTemplate: false,
    },
  ],
}

/** A preview like the backend's: the client name is marked missing until it has a value. */
function previewFor(values: Record<string, MetadataValue>): Preview {
  const client = values.clientName
  const name =
    client?.type === 'Text'
      ? `<span class="dms-value" data-field="clientName">${client.value}</span>`
      : '<span class="dms-missing dms-missing-required" data-field="clientName" data-required="true">Client name</span>'
  return {
    html: `<article class="dms-document dms-draft" lang="en" data-watermark="Draft – not valid"><div class="dms-draft-banner" role="note">Draft – not valid</div><p class="dms-paragraph">Between ${name}.</p></article>`,
    missingRequired: [
      ...(client ? [] : [{ field: 'clientName', label: 'Client name' }]),
      ...(values.monthlyFee ? [] : [{ field: 'monthlyFee', label: 'Monthly fee' }]),
    ],
    issues: [],
  }
}

interface PreviewBody {
  values: Record<string, MetadataValue>
  language: string
}

function setup() {
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/templates') {
      return Promise.resolve({
        data: [
          {
            templateId,
            version: 2,
            name: 'Service contract',
            description: 'For managed services',
            documentClassId: fields.documentClassId,
            documentClassVersion: 1,
          },
        ],
        response: new Response(null, { status: 200 }),
      })
    }
    return Promise.resolve({ data: fields, response: new Response(null, { status: 200 }) })
  })
  const post = vi.fn((_path: string, init: { body: PreviewBody }) =>
    Promise.resolve({
      data: previewFor(init.body.values),
      response: new Response(null, { status: 200 }),
    }),
  )
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <NewDocumentPage />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  const lastBody = (): PreviewBody | undefined => post.mock.calls.at(-1)?.[1].body
  return { get, post, lastBody }
}

/** The control labelled `label`, with or without the required marker. */
function fieldControl(label: string): HTMLElement
function fieldControl(label: string, find: true): Promise<HTMLElement>
function fieldControl(label: string, find?: true): HTMLElement | Promise<HTMLElement> {
  const matcher = new RegExp(`^${label}\\*?$`)
  return find ? screen.findByLabelText(matcher) : screen.getByLabelText(matcher)
}

async function chooseTemplate() {
  const user = userEvent.setup()
  const select = await screen.findByLabelText('Template')
  await waitFor(() => {
    expect(select).toBeEnabled()
  })
  await user.selectOptions(select, 'Service contract (version 2)')
  await fieldControl('Client name', true)
  return user
}

describe('NewDocumentPage', () => {
  it('generates the form from the template fields with required markers', async () => {
    const { get } = setup()
    await chooseTemplate()

    expect(get).toHaveBeenCalledWith('/api/v1/templates/{id}/versions/{version}/fields', {
      params: { path: { id: templateId, version: 2 } },
    })
    expect(fieldControl('Client name')).toHaveAttribute('aria-required', 'true')
    expect(fieldControl('Monthly fee')).toHaveAttribute('aria-required', 'true')
    expect(fieldControl('Term in months')).not.toHaveAttribute('aria-required')
    expect(screen.getByLabelText('Monthly fee currency')).toHaveValue('EUR')
    expect(within(fieldControl('Term')).getByText('Fixed term')).toBeInTheDocument()
    expect(fieldControl('Start date')).toHaveAttribute('type', 'date')
    expect(within(fieldControl('International')).getByText('Yes')).toBeInTheDocument()
    expect(fieldControl('Tags').tagName).toBe('TEXTAREA')
    expect(screen.getByRole('group', { name: 'Template fields' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Further metadata' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled()
  })

  it('shows the draft preview with missing values and updates it debounced after changes', async () => {
    const { post, lastBody } = setup()
    const user = await chooseTemplate()

    // Initial preview with no values: draft marking and missing required values.
    const preview = await screen.findByTestId('document-preview')
    expect(within(preview).getByText('Draft – not valid')).toBeInTheDocument()
    expect(screen.getAllByText('Draft – not valid').length).toBeGreaterThan(1)
    expect(within(preview).getByText('Client name')).toHaveClass('dms-missing-required')
    expect(screen.getByText('2 required values are missing')).toBeInTheDocument()
    expect(lastBody()).toEqual({ values: {}, language: 'en' })
    const callsBefore = post.mock.calls.length

    await user.type(fieldControl('Client name'), 'ACME')

    await waitFor(() => {
      expect(lastBody()?.values).toEqual({ clientName: { type: 'Text', value: 'ACME' } })
    })
    // Typing four characters quickly sends one request, not four.
    expect(post.mock.calls.length - callsBefore).toBe(1)
    expect(await within(preview).findByText('ACME')).toHaveClass('dms-value')
    expect(screen.getByText('1 required value is missing')).toBeInTheDocument()

    await user.type(fieldControl('Monthly fee'), '1250.5')
    await user.selectOptions(screen.getByLabelText('Monthly fee currency'), 'CHF')
    await user.selectOptions(fieldControl('Document language'), 'German')
    await waitFor(() => {
      expect(lastBody()).toEqual({
        values: {
          clientName: { type: 'Text', value: 'ACME' },
          monthlyFee: { type: 'Amount', value: 1250.5, currency: 'CHF' },
        },
        language: 'de',
      })
    })
    await waitFor(() => {
      expect(screen.queryByText(/required values? (is|are) missing/)).not.toBeInTheDocument()
    })
  })

  it('validates inline and leaves invalid values out of the preview', async () => {
    const { lastBody } = setup()
    const user = await chooseTemplate()
    await screen.findByTestId('document-preview')

    const term = fieldControl('Term in months')
    await user.type(term, '500')

    expect(await screen.findByText('Must be at most 120.')).toBeInTheDocument()
    expect(term).toHaveAttribute('aria-invalid', 'true')
    await new Promise((resolve) => setTimeout(resolve, 400))
    expect(lastBody()?.values).toEqual({})

    await user.clear(term)
    await user.type(term, '36')
    expect(screen.queryByText('Must be at most 120.')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(lastBody()?.values).toEqual({ termMonths: { type: 'Number', value: 36 } })
    })
  })

  it('focuses the form field when a missing value in the preview is clicked', async () => {
    setup()
    const user = await chooseTemplate()
    const preview = await screen.findByTestId('document-preview')

    await user.click(within(preview).getByText('Client name'))
    expect(fieldControl('Client name')).toHaveFocus()

    await user.click(screen.getByRole('button', { name: 'Monthly fee' }))
    expect(fieldControl('Monthly fee')).toHaveFocus()
  })
})
