import type { Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/lowering'

export const lowering: Translation<typeof en.lowering> = {
  title: 'Vertraulichkeit herabstufen',
  hint: 'Diese Version steht auf {level}. Tiefer geht es nur mit der Freigabe einer zweiten Person.',
  accessHint:
    'Diese Version nennt {level}. Bis zur Veröffentlichung bleibt der Zugriff auf {access}: Nur wer dafür berechtigt ist, sieht sie.',
  offer:
    'Unter {level} braucht diese Version eine zusätzliche Freigabe: Es entscheidet jemand, der für die aktuelle Stufe berechtigt ist und die Version nicht geändert hat. Bis dahin bleibt der Entwurf auf seiner Stufe, und bereits gültige Versionen bleiben unberührt.',
  loading: 'Anträge zur Herabstufung werden geladen …',
  failed: 'Die Anträge zur Herabstufung konnten nicht geladen werden.',
  fromTo: '{from} → {to}',
  requestedBy: 'Beantragt von {name} am {date}',
  approvedBy: 'Freigegeben von {name} am {date}',
  refusedBy: 'Abgelehnt von {name} am {date}',
  withdrawnBy: 'Zurückgezogen von {name} am {date}',
  actingFor: 'i. V. für {name}',
  step: 'Freigabe der niedrigeren Vertraulichkeit',
  decisionHint:
    'Die Freigabe senkt die Stufe nur für diese Version und erst ab ihrer Veröffentlichung. Eine Ablehnung gibt die Version auf der alten Stufe an die Autorin oder den Autor zurück.',
  status: {
    Requested: 'Offen',
    Approved: 'Freigegeben',
    Refused: 'Abgelehnt',
    Withdrawn: 'Zurückgezogen',
  },
  state: {
    draft:
      'Wartet auf das Einreichen: Der Workflow erhält dann den zusätzlichen Freigabeschritt. Der Entwurf bleibt auf seiner Stufe.',
    inWorkflow: 'Wird im zusätzlichen Freigabeschritt des Workflows entschieden.',
    approved:
      'Freigegeben: {level} gilt mit der Veröffentlichung dieser Version. Bis dahin bleibt der Zugriff auf {access}.',
    inForce: 'Wirksam: Diese Version steht seit ihrer Veröffentlichung auf {level}.',
    refused:
      'Abgelehnt: Die Version bleibt auf {level}. Sie können mit neuer Begründung erneut beantragen.',
    withdrawn: 'Der Antrag wurde zurückgezogen.',
  },
  request: {
    action: 'Niedrigere Stufe beantragen',
    level: 'Neue Stufe',
    reason: 'Begründung (erforderlich)',
    hint: 'Die Begründung lesen die Freigebenden und alle, die für die aktuelle Stufe berechtigt sind. Der Antrag zählt als Änderung dieser Version: Sie können sie danach nicht mehr freigeben.',
    reasonMissing: 'Bitte geben Sie eine Begründung an.',
    reasonTooLong: 'Die Begründung darf höchstens {max} Zeichen haben.',
    send: 'Antrag senden',
    sending: 'Wird gesendet …',
    done: 'Der Antrag ist erfasst.',
    forbidden: 'Sie dürfen diesen Entwurf nicht ändern.',
    conflict:
      'Der Antrag konnte nicht erfasst werden: Die Version oder ihre Anträge haben sich geändert. Bitte laden Sie neu.',
    failed: 'Der Antrag konnte nicht erfasst werden: {message}',
  },
  withdraw: {
    action: 'Antrag zurückziehen',
    done: 'Der Antrag ist zurückgezogen.',
    forbidden: 'Sie dürfen diesen Entwurf nicht ändern.',
    conflict:
      'Der Antrag lässt sich nicht mehr zurückziehen: Er ist entschieden, oder die Version ist weiter.',
    failed: 'Der Antrag konnte nicht zurückgezogen werden: {message}',
  },
}
