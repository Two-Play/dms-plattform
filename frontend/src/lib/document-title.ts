import { useEffect } from 'react'

/** Product name shown in the browser tab after the page title. */
export const APP_NAME = 'DMS'

export function documentTitle(title: string | undefined): string {
  return title ? `${title} · ${APP_NAME}` : APP_NAME
}

/** Sets the browser tab title, e.g. "Tasks · DMS". */
export function useDocumentTitle(title: string | undefined): void {
  useEffect(() => {
    document.title = documentTitle(title)
  }, [title])
}
