import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { WordField } from '@/api/word'
import { setLocale } from '@/i18n'
import { WordFieldInput, WordFieldValue } from './word-field-input'

const field: WordField = {
  key: 'quantity',
  label: 'Quantity',
  type: 'Number',
  source: 'Manual',
  required: false,
}

function Form({ initial, onValue }: { initial: string; onValue: (value: string) => void }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <label htmlFor="q">Quantity</label>
      <WordFieldInput
        id="q"
        field={field}
        value={value}
        onChange={(next) => {
          setValue(next)
          onValue(next)
        }}
      />
    </>
  )
}

describe('number fields of Word documents (DMS-165)', () => {
  it('reads what people type by the UI language and stores the plain number', async () => {
    setLocale('de')
    const onValue = vi.fn()
    const user = userEvent.setup()
    render(<Form initial="" onValue={onValue} />)

    await user.type(screen.getByLabelText('Quantity'), '1.250,5')

    expect(onValue).toHaveBeenLastCalledWith('1250.5')
    expect(screen.getByLabelText('Quantity')).toHaveValue('1.250,5')
  })

  it('shows a stored number in the format of the language and leaves it alone', () => {
    setLocale('de')
    const onValue = vi.fn()
    render(<Form initial="1250.5" onValue={onValue} />)

    expect(screen.getByLabelText('Quantity')).toHaveValue('1.250,5')
    expect(onValue).not.toHaveBeenCalled()
  })

  it('never hands on a form it would have to guess as a number', async () => {
    setLocale('de')
    const onValue = vi.fn()
    const user = userEvent.setup()
    render(<Form initial="" onValue={onValue} />)

    await user.type(screen.getByLabelText('Quantity'), '1.25')

    expect(screen.getByLabelText('Quantity')).toHaveValue('1.25')
    expect(screen.getByLabelText('Quantity')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('Unklare Schreibweise')
    // The backend reads a point as decimal separator, so the plain text must not reach it as a number.
    expect(Number(onValue.mock.lastCall?.[0])).toBeNaN()
  })

  it('shows a stored number read-only in the format of the language', () => {
    setLocale('de')
    render(<WordFieldValue field={field} value="1250.5" />)
    expect(screen.getByText('1.250,5')).toBeInTheDocument()
  })
})
