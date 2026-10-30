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
  causes: {
    unavailable:
      'The rendering or conversion service or the object storage could not be reached. This is usually temporary.',
    rejected:
      'The rendering service refused the content, e.g. because it references an external resource.',
    not_archivable:
      'The PDF does not pass the check against the archive standard (PDF/A-2b), e.g. because of a font that is not embedded.',
    invalid: 'The version cannot be rendered in its current state.',
    integrity: 'A stored file does not match its checksum.',
    database: 'The database could not be reached.',
    unknown: 'The cause is unknown.',
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
