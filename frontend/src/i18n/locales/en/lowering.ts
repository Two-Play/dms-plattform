/** Lowering the confidentiality of a later version with a second person's approval (DMS-162, ADR 0083). */
export const lowering = {
  title: 'Lower confidentiality',
  hint: 'This version stands at {level}. Going lower needs the approval of a second person.',
  levelOnly: 'This version stands at {level}.',
  accessHint:
    'This version states {level}. Until it is published, access stays at {access}: only people cleared for that level see it.',
  accessHintEnded:
    'This version states {level}, but it was never published, so that level never took effect: access stays at {access}.',
  requests: 'Requests to lower the confidentiality',
  offer:
    'Below {level} this version needs an additional approval: someone cleared for the current level who did not change the version decides. The draft stays at its level until then, and versions already valid are not touched.',
  loading: 'Loading the requests to lower the confidentiality …',
  failed: 'The requests to lower the confidentiality could not be loaded.',
  fromTo: '{from} → {to}',
  requestedBy: 'Requested by {name} on {date}',
  approvedBy: 'Approved by {name} on {date}',
  refusedBy: 'Refused by {name} on {date}',
  withdrawnBy: 'Withdrawn by {name} on {date}',
  actingFor: 'on behalf of {name}',
  step: 'Approval of the lower confidentiality',
  decisionHint:
    'Approving lowers the level for this version only, from its publication on. Rejecting returns the version to its author at the old level.',
  status: {
    Requested: 'Open',
    Undecided: 'Not decided',
    Approved: 'Approved',
    Refused: 'Refused',
    Withdrawn: 'Withdrawn',
  },
  state: {
    draft:
      'Waiting for submission: the workflow then gets the additional approval step. The draft stays at its level.',
    inWorkflow: 'Decided in the additional approval step of the workflow.',
    approved:
      'Approved: {level} applies with the publication of this version. Until then access stays at {access}.',
    neverInForce:
      'Approved, but never in force: the version was not published, so {level} did not take effect and access stays at {access}.',
    inForce: 'In force: this version has been at {level} since its publication.',
    tookEffect:
      '{level} took effect with the publication of this version, which is no longer valid.',
    refused: 'Refused: the version stays at {level}. You can ask again with a new reason.',
    refusedClosed: 'Refused: this request changed nothing.',
    undecided: 'Not decided: the version went no further and stays at {level}.',
    withdrawn: 'The request was withdrawn.',
  },
  request: {
    action: 'Ask for a lower level',
    level: 'New level',
    reason: 'Reason (required)',
    hint: 'The approver and those cleared for the current level read the reason. Asking counts as a change of this version: you cannot approve it afterwards.',
    reasonMissing: 'Please give a reason.',
    reasonTooLong: 'The reason can have at most {max} characters.',
    send: 'Send request',
    sending: 'Sending …',
    done: 'The request is recorded.',
    forbidden: 'You may not change this draft.',
    conflict:
      'The request could not be recorded: the version or its requests changed. Please reload.',
    failed: 'The request could not be recorded: {message}',
  },
  withdraw: {
    action: 'Withdraw request',
    done: 'The request is withdrawn.',
    forbidden: 'You may not change this draft.',
    conflict: 'The request can no longer be withdrawn: it was decided or the version moved on.',
    failed: 'The request could not be withdrawn: {message}',
  },
} as const
