import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { TemplateField } from '@/api/templates'
import { DocumentForm } from './document-form'

const services: TemplateField = {
  key: 'services',
  label: 'Services',
  fieldType: {
    type: 'Selection',
    options: [
      { code: 'hosting', label: 'Hosting', active: true },
      { code: 'fax', label: 'Fax service', active: false },
      { code: 'telex', label: 'Telex', active: false },
    ],
  },
  multiple: true,
  required: true,
  usedInTemplate: true,
}

describe('multiple selections (DMS-165)', () => {
  it('keeps a stored option that is no longer offered visible, and no others', () => {
    render(
      <DocumentForm
        fields={[services]}
        inputs={{ services: ['fax'] }}
        errors={{}}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByRole('checkbox', { name: 'Fax service' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /Hosting/ })).not.toBeChecked()
    expect(screen.queryByRole('checkbox', { name: 'Telex' })).not.toBeInTheDocument()
  })

  it('marks the group as invalid and links the message', () => {
    render(
      <DocumentForm
        fields={[services]}
        inputs={{ services: ['fax'] }}
        errors={{ services: 'Choose one of the options.' }}
        onChange={vi.fn()}
      />,
    )

    const group = screen.getByRole('group', { name: /^Services/ })
    expect(group).toHaveAttribute('aria-invalid', 'true')
    expect(group).toHaveAttribute('aria-required', 'true')
    expect(group).toHaveAccessibleDescription('Choose one of the options.')
  })
})
