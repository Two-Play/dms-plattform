/** The page of the failures a person's role covers or they are named for (DMS-146). */
export const failures = {
  title: 'Failed processing',
  description:
    'Archive PDFs and publications that failed for good and that you can retry or that your role covers.',
} as const

/** Archive renditions: state, failures and retry (DMS-93). */
export const renditions = {
  loading: 'Loading the state of the archive PDF',
  pending: 'The archive PDF is being produced; the version is approved afterwards.',
  retrying:
    'Producing the archive PDF failed (attempt {attempt} of {max}). It is retried automatically, next on {date}.',
  failed:
    'The archive PDF could not be produced: all {max} attempts failed. The version stays in approval until the rendering is retried.',
  done: 'The archive PDF has been produced.',
  none: 'No archive PDF was requested for this version.',
  cause: 'Cause:',
  technicalDetail: 'Technical detail',
  withdrawHint:
    'If a retry cannot help, you can withdraw the version with the action above and then start a new one.',
  withdrawHintOthers:
    'If a retry cannot help, holders of the owner role or those who approved the version can withdraw it; a new version can then be started.',
  causes: {
    unavailable:
      'The rendering or conversion service or the object storage could not be reached. This is usually temporary.',
    rejected:
      'The rendering service refused the content, e.g. because it references an external resource.',
    not_archivable:
      'The PDF does not pass the check against the archive standard (PDF/A-2b), e.g. because of a font that is not embedded.',
    invalid: 'The version cannot be rendered in its current state.',
    too_complex: 'The original file is too large or too complex to fill its fields.',
    integrity: 'A stored file does not match its checksum.',
    segregation_of_duties:
      'The last approval came from someone who changed this version; the four-eyes rule does not allow it. A retry cannot help.',
    database: 'The database could not be reached.',
    timeout: 'The rendering did not finish in time.',
    internal: 'An internal error occurred in the platform.',
    unknown: 'The processing stopped; the technical detail says more.',
  },
  retry: 'Retry now',
  retryPending: 'Retrying …',
  retried: 'The rendering of version {version} was requeued.',
  retryForbidden: 'You are not allowed to retry this rendering.',
  retryConflict: 'The rendering cannot be retried: {message}',
  retryFailed: 'The rendering could not be requeued: {message}',
  failures: {
    title: 'Failed archive PDFs',
    description:
      'Versions whose archive PDF failed or is being retried. They stay in approval until it is produced.',
    loading: 'Loading failed archive PDFs',
    empty: 'No archive PDF has failed.',
    caption: 'Versions with failed archive PDFs',
    document: 'Document',
    version: 'Version',
    state: 'State',
    attempts: 'Attempts',
    cause: 'Cause',
    action: 'Action',
    confidential: 'Confidential document',
    states: {
      Failed: 'Failed',
      Retrying: 'Being retried',
      Pending: 'Pending',
      Done: 'Done',
      None: 'Not requested',
    },
    nextAttempt: 'next attempt {date}',
  },
} as const
