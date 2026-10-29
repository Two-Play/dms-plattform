import { plural } from '@/i18n/core'

/** Texts used across pages: actions, the shell, page building blocks, errors and status labels. */

export const common = {
  product: 'DMS Platform',
  loading: 'Loading…',
  retry: 'Try again',
  cancel: 'Cancel',
  close: 'Close',
  save: 'Save',
  reload: 'Reload',
  document: 'Document',
  version: 'Version {label}',
  none: '–',
  yes: 'Yes',
  no: 'No',
  breadcrumb: 'Breadcrumb',
  overdue: 'Overdue',
  comingSoon: 'Coming soon',
} as const

export const shell = {
  skipToContent: 'Skip to content',
  mainNav: 'Main',
  openMenu: 'Open menu',
  search: 'Search documents',
  searchPlaceholder: 'Search documents…',
  openTasks: plural({ one: '{count} open task', other: '{count} open tasks' }),
  userMenu: 'User menu',
  signedInUser: 'Signed-in user',
  deputies: 'Deputies',
  signOut: 'Sign out',
  switchToDark: 'Switch to dark mode',
  switchToLight: 'Switch to light mode',
  language: 'Language',
  languages: { de: 'Deutsch', en: 'English' },
  nav: {
    home: 'Home',
    documents: 'Documents',
    library: 'Library',
    search: 'Search',
    tasks: 'Tasks',
    templates: 'Templates',
    compliance: 'Compliance',
    admin: 'Admin',
  },
} as const

export const auth = {
  intro: 'Document and template management with approval workflows and audit-proof archiving.',
  signIn: 'Sign in',
  signInFailed: 'Sign-in failed',
  signInFailedWith: 'Sign-in failed: {message}',
  noSignIn: 'No sign-in in progress',
  signingIn: 'Signing in…',
} as const

export const notFound = {
  title: 'Page not found',
  text: 'The page you are looking for does not exist.',
  home: 'Go to start page',
} as const

export const shared = {
  notifications: 'Notifications',
  dismiss: 'Dismiss notification',
} as const

/** The people picker (DMS-111): search a person by name or email instead of typing a user id. */
export const people = {
  label: 'Select a person',
  placeholder: 'Name or email…',
  hint: 'Type at least {count} characters of the name or email.',
  results: 'People found',
  searching: 'Searching…',
  found: plural({ one: '{count} person found.', other: '{count} people found.' }),
  none: 'Nobody found for “{query}”.',
  clear: 'Clear selection',
  picked: 'Selected people',
  remove: 'Remove {name}',
  useId: 'Use “{id}” as user id',
  unavailable: 'People cannot be searched right now.',
  unavailableHint: 'You can enter the user id instead.',
} as const

export const system = {
  title: 'System status',
  description: 'Backend health check',
  loading: 'Loading system status',
  unavailable: 'The backend is unavailable',
  status: 'Status',
  version: 'Version',
} as const

export const errors = {
  network: {
    title: 'No connection to the server',
    description: 'Check your network connection and try again.',
    inline: 'No connection to the server. Check your network connection and try again.',
  },
  unauthorized: {
    title: 'Your session has expired',
    description: 'Sign in again to continue. Reloading the page signs you in again.',
    inline: 'Your session has expired. Reload the page to sign in again.',
  },
  forbidden: {
    title: 'You are not allowed to see this',
    description: 'Ask your administrator if you need access.',
    inline: 'You are not allowed to do this.',
  },
  notFound: {
    title: 'Not found',
    description: 'It may have been deleted, or the link is wrong.',
    inline: 'Not found. It may have been deleted in the meantime.',
  },
  conflict: {
    title: 'This was changed in the meantime',
    description: 'Reload to see the current state, then try again.',
    inline: 'This was changed in the meantime. Reload to see the current state.',
  },
  invalid: {
    title: 'The request could not be processed',
    description: 'Check the entered values and try again.',
    inline: 'The request could not be processed. Check the entered values.',
  },
  server: {
    title: 'Something went wrong on the server',
    description: 'Try again in a moment. If the problem persists, contact support.',
    inline: 'Something went wrong on the server. Try again in a moment.',
  },
  unknown: {
    title: 'Something went wrong',
    description: 'Try again. If the problem persists, contact support.',
    inline: 'Something went wrong. Try again.',
  },
  partnerUnavailable:
    'The partner master is not reachable right now. You can save the draft without the partner and pick it later.',
} as const

/** Labels of statuses, kinds and decisions the API sends as identifiers. */
export const status = {
  Draft: 'Draft',
  InReview: 'In review',
  InApproval: 'In approval',
  Approved: 'Approved',
  Published: 'Published',
  ReviewDue: 'Review due',
  Superseded: 'Superseded',
  Withdrawn: 'Withdrawn',
  Archived: 'Archived',
  Destroyed: 'Destroyed',
  Running: 'Running',
  Returned: 'Returned',
  Rendering: 'Rendering',
  Open: 'Open',
  Waiting: 'Waiting',
  Completed: 'Completed',
  Cancelled: 'Cancelled',
  Testing: 'Testing',
  Released: 'Released',
  Deprecated: 'Deprecated',
  Scheduled: 'Scheduled',
  Active: 'Active',
  Ended: 'Ended',
  Approve: 'Approve',
  ApproveWithConditions: 'Approve with conditions',
  RequestChanges: 'Request changes',
  Reject: 'Reject',
  Review: 'Review',
  Approval: 'Approval',
  Major: 'Major',
  Minor: 'Minor',
} as const
