function required(name: string, value: string | undefined): string {
  if (!value) {
    throw new Error(`Missing environment variable ${name}. Copy .env.example to .env.local.`)
  }
  return value
}

/** Runtime configuration from Vite env variables (see `.env.example`). */
export const config = {
  oidcIssuer: import.meta.env.VITE_OIDC_ISSUER ?? 'http://localhost:8085',
  get oidcClientId(): string {
    return required('VITE_OIDC_CLIENT_ID', import.meta.env.VITE_OIDC_CLIENT_ID)
  },
  /** Optional ZITADEL project id; adds the project audience scope when set. */
  oidcProjectId: import.meta.env.VITE_OIDC_PROJECT_ID,
  /** Defaults to `/api`, which the Vite dev server proxies to the backend. */
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
}
