/** Scheduled publications: failures and retry (DMS-142). */
export const publications = {
  retrying:
    'The publication of this version failed (attempt {attempt} of {max}). It is retried automatically, next on {date}.',
  failed:
    'This version could not be published: all {max} attempts failed. It stays approved and does not become valid until the publication is retried.',
  cause: 'Cause:',
  technicalDetail: 'Technical detail',
  causes: {
    conflict: 'The state of the document does not allow the publication.',
    legal_hold: 'A legal hold is active on the document.',
    invalid: 'The version cannot be published in its current state.',
    not_found: 'The version was not found.',
    database: 'The database could not be reached.',
    unknown: 'The cause is unknown.',
  },
  retry: 'Retry now',
  retryPending: 'Retrying …',
  retried: 'The publication of version {version} was requeued.',
  retryForbidden: 'You are not allowed to retry this publication.',
  retryConflict: 'The publication cannot be retried: {message}',
  retryFailed: 'The publication could not be requeued: {message}',
  failures: {
    title: 'Failed publications',
    description:
      'Approved versions whose publication failed or is being retried. They do not become valid until it succeeds.',
    loading: 'Loading failed publications',
    empty: 'No publication has failed.',
    caption: 'Versions with failed publications',
    document: 'Document',
    version: 'Version',
    state: 'State',
    cause: 'Cause',
    action: 'Action',
    confidential: 'Confidential document',
    failed: 'Failed',
    retrying: 'Being retried',
    nextAttempt: 'next attempt {date}',
  },
} as const
