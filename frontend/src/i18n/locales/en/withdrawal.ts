/** Withdrawing an approved version before its valid-from date (DMS-143). */
export const withdrawVersion = {
  action: 'Withdraw version {label}',
  title: 'Withdraw version {label}?',
  description:
    'Version {label} would become valid on {validFrom}. If you withdraw it, it is never published and version {valid} stays valid. Readers are not notified; the approvers and the owner are. This cannot be undone.',
  descriptionNoValid:
    'Version {label} would become valid on {validFrom}. If you withdraw it, it is never published and the document has no valid version until a new one is approved. The approvers and the owner are notified. This cannot be undone.',
  reason: 'Reason',
  reasonHint:
    'Why must this version not take effect? Shown in the version history and recorded in the audit trail.',
  reasonMissing: 'Please give a reason.',
  reasonTooLong: 'At most {max} characters ({count} entered).',
  confirm: 'Withdraw version',
  confirming: 'Withdrawing …',
  withdrawn: 'Version {label} withdrawn; version {valid} stays valid.',
  withdrawnNoValid: 'Version {label} withdrawn; the document has no valid version.',
  forbidden: 'You may not withdraw this version.',
  conflict: 'The version was not withdrawn: {message}',
  failed: 'The version was not withdrawn: {message}',
  refused: {
    version_published:
      'The version was not withdrawn: it was published first and is valid now. It can only be taken out of force as a valid version.',
    version_due:
      'The version was not withdrawn: its valid-from date has come and it is being published. It can only be taken out of force as a valid version.',
    version_withdrawn: 'Somebody else has withdrawn this version already.',
    version_not_approved: 'The version was not withdrawn: it is not approved.',
  },
  neverValid: 'Withdrawn on {date} by',
  neverValidReason: 'Reason: {reason}',
  notice: 'This version was withdrawn before it took effect and was never valid.',
} as const
