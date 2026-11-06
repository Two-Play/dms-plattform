import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
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

  it('returns focus to the button that opened a dialog without a trigger', async () => {
    const user = userEvent.setup()
    function Opener() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button
            type="button"
            onClick={() => {
              setOpen(true)
            }}
          >
            Open
          </button>
          <Dialog open={open} onOpenChange={setOpen}>
            <DialogContent>
              <DialogTitle>Title</DialogTitle>
              <DialogDescription>Text</DialogDescription>
            </DialogContent>
          </Dialog>
        </>
      )
    }
    render(<Opener />)
    await user.click(screen.getByRole('button', { name: 'Open' }))
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus()
    })
  })
})
