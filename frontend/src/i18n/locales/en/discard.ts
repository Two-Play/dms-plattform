/** Discarding a draft on the document page (DMS-129). */
export const discardDraft = {
  action: 'Discard draft',
  title: 'Discard draft?',
  descriptionFirst:
    'The draft {label} is the first version of this document. Discarding it removes the document from lists, search and the task inbox; the audit trail keeps who discarded it, when and why. This cannot be undone.',
  descriptionNew:
    'Only the draft {label} is discarded; the valid version stays unchanged and you can start a new version again. This cannot be undone.',
  attachmentsNote:
    'Attachments only this draft uses are deleted; files of other versions are not affected.',
  reason: 'Reason (optional)',
  reasonHint: 'E.g. "created by accident". Recorded in the audit trail.',
  reasonTooLong: 'At most {max} characters ({count} entered).',
  confirm: 'Discard draft',
  confirming: 'Discarding …',
  discardedDocument: 'Draft {label} discarded; the document was removed.',
  discardedVersion: 'Draft {label} discarded; version {valid} stays valid.',
  forbidden: 'You may not discard this draft.',
  conflict: 'The draft was not discarded: {message}',
  failed: 'The draft was not discarded: {message}',
} as const
