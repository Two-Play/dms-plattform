/** Deadlines of review and approval tasks in the administration (DMS-87). */
export const deadlines = {
  tab: 'Deadlines',
  title: 'Deadlines of reviews and approvals',
  description:
    'Working days (Monday to Friday) a task has, when its participants are reminded and when it is escalated. A workflow or one of its steps may set its own; changes apply to tasks created from now on.',
  loading: 'Loading the deadlines',
  sourceTenant: 'Your organization sets these deadlines.',
  sourceInstallation:
    'The platform default applies: due after {due} working days, reminder {reminder} before, escalation {escalation} after.',
  fields: {
    dueWorkingDays: 'Due after (working days)',
    reminderWorkingDays: 'Reminder before (working days)',
    escalationWorkingDays: 'Escalation after (working days)',
  },
  hints: {
    dueWorkingDays: '0: no deadlines.',
    reminderWorkingDays: '0: no reminder.',
    escalationWorkingDays: 'After the deadline; 0: no escalation.',
  },
  invalidDays: 'A whole number from 0 to 60.',
  reminderTooLate: 'The reminder must come before the deadline.',
  escalateTo: 'Escalate to',
  escalateToHint:
    'At the deadline the participants and their deputies are told; on escalation also these people.',
  owner: 'Owner of the document',
  save: 'Save',
  saving: 'Saving …',
  saved: 'Deadlines saved.',
  reset: 'Use the platform default',
  resetDone: 'The platform default applies again.',
  failed: 'The deadlines were not saved: {message}',
} as const
