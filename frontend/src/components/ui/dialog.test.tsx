import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { setLocale } from '@/i18n'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from './dialog'

describe('DialogContent', () => {
  it('scrolls within the viewport and names its close button in the language of the user', () => {
    setLocale('de')
    render(
      <Dialog open>
        <DialogContent>
          <DialogTitle>Title</DialogTitle>
          <DialogDescription>Text</DialogDescription>
        </DialogContent>
      </Dialog>,
    )
    expect(screen.getByRole('dialog')).toHaveClass('max-h-[calc(100dvh-2rem)]', 'overflow-y-auto')
    expect(screen.getByRole('button', { name: 'Schließen' })).toBeInTheDocument()
  })
})
