/** Withdrawing an approved version before its valid-from date (DMS-143) or after its publication failed (DMS-145). */
export const withdrawVersion = {
  action: 'Withdraw version {label}',
  title: 'Withdraw version {label}?',
  description:
    'Version {label} would become valid on {validFrom}. If you withdraw it, it is never published and version {valid} stays valid. Readers are not notified; the author, the approvers and the owner are. This cannot be undone.',
  descriptionNoValid:
    'Version {label} would become valid on {validFrom}. If you withdraw it, it is never published and the document has no valid version until a new one is approved. The author, the approvers and the owner are notified. This cannot be undone.',
  descriptionFailed:
    'The publication of version {label} failed for good. If you withdraw it, it is never published, version {valid} stays valid and a new version can be started. Readers are not notified; the author, the approvers and the owner are. This cannot be undone.',
  descriptionFailedNoValid:
    'The publication of version {label} failed for good. If you withdraw it, it is never published and a new version can be started; the document has no valid version until that one is approved. The author, the approvers and the owner are notified. This cannot be undone.',
  descriptionUnrendered:
    'The archive PDF of version {label} could not be produced, so its approval cannot finish. If you withdraw it, it is never approved or published, version {valid} stays valid and a new version can be started. Readers are not notified; the author, the approvers and the owner are. This cannot be undone.',
  descriptionUnrenderedNoValid:
    'The archive PDF of version {label} could not be produced, so its approval cannot finish. If you withdraw it, it is never approved or published and a new version can be started from it; the document has no valid version until that one is approved. The author, the approvers and the owner are notified. This cannot be undone.',
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
      'The version was not withdrawn: its valid-from date has come and it is being published. It can only be taken out of force as a valid version, or withdrawn here if every attempt to publish it fails.',
    version_withdrawn: 'Somebody else has withdrawn this version already.',
    version_not_approved: 'The version was not withdrawn: it is not approved.',
  },
  actingFor: 'You approved this version as deputy. The withdrawal is recorded on behalf of',
  neverValid: 'Withdrawn on {date} by',
  neverValidActingFor: 'on behalf of',
  neverValidReason: 'Reason: {reason}',
  notice: 'This version was withdrawn before it took effect and was never valid.',
} as const
