export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'dms-theme'

export function currentTheme(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

/** Switches the `dark` class on <html>; the CSS variables in index.css do the rest. */
export function setTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // Storage can be unavailable (private mode); the choice then lasts for this page only.
  }
}
