import { useRef } from 'react'

/** The `h1` of the current page. */
export function pageHeading(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-page-heading]')
}

/**
 * Where focus goes when a dialog closes, for dialogs whose action can remove the button that opened them.
 * `remember` is called when opening, with the position of the opener in its list. `restore` is the
 * dialog's `onCloseAutoFocus`: focus returns to the opener, or to `fallback` if the opener is gone. The
 * list may still change with the render after closing, so the target is chosen in the next frame.
 */
export function useReturnFocus(fallback: (index: number) => HTMLElement | null | undefined) {
  const opener = useRef<{ element: Element | null; index: number }>({ element: null, index: 0 })
  return {
    remember: (index = 0) => {
      opener.current = { element: document.activeElement, index }
    },
    restore: (event: Event) => {
      event.preventDefault()
      requestAnimationFrame(() => {
        const { element, index } = opener.current
        const back =
          element instanceof HTMLElement && element !== document.body && element.isConnected
        ;(back ? element : fallback(index))?.focus()
      })
    },
  }
}
