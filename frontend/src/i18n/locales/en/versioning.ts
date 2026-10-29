/** Starting a new version and its change summary on the document page (DMS-103). */
export const newVersion = {
  start: 'New version',
  title: 'Start a new version',
  description:
    'Starts the next version as a draft from the valid version {label}. Content, field values and attachments are taken over; the valid version stays valid until the new one is published.',
  fileDescription:
    'Starts the next version as a draft from the valid version {label}. The file, its field values and the attachments are taken over; upload the revised file in the draft afterwards.',
  kind: 'Kind of change',
  major: 'Change of content',
  majorHint: 'Full review and approval workflow; readers acknowledge the new version again.',
  minor: 'Editorial correction',
  minorHint:
    'E.g. typos, wording or broken links without a change of meaning; the owner confirms it in a shortened workflow.',
  becomes: 'becomes version {label}',
  summary: 'Change summary *',
  summaryHint:
    'What changes and why, in plain words for readers. Shown in the version history and above version comparisons; you can change it until you submit the draft.',
  summaryMissing: 'Describe what changes and why: every new version needs a change summary.',
  summaryTooLong: 'At most {max} characters ({count} entered).',
  submit: 'Start version',
  submitting: 'Starting …',
  started: 'Version {label} started as a draft.',
  startedFile: 'Version {label} started as a draft. Upload the revised file.',
  forbidden: 'You may not start a new version of this document.',
  conflict: 'The version was not started: {message}',
  failed: 'The version was not started: {message}',
  blocked: {
    OpenVersion: 'A new version is already in progress; finish it first.',
    ScheduledVersion:
      'An approved version waits for its valid-from date; start the next version once it is valid.',
  },
  blockedOther: 'A new version cannot be started right now.',
} as const

export const changeSummary = {
  title: 'Change summary',
  kind: 'Kind of change: {kind}',
  empty: 'No change summary yet.',
  edit: 'Edit change summary',
  label: 'Change summary',
  labelRequired: 'Change summary *',
  hint: 'You can change the summary until you submit the draft.',
  missing: 'Every new version needs a change summary.',
  save: 'Save summary',
  saving: 'Saving …',
  saved: 'Change summary saved.',
  forbidden: 'You may not change the change summary of this draft.',
  failed: 'Not saved: {message}',
} as const
