/** Fristen von Prüf- und Freigabeaufgaben in der Administration (DMS-87). */
export const deadlines = {
  tab: 'Fristen',
  title: 'Fristen für Prüfungen und Freigaben',
  description:
    'Wie viele Arbeitstage (Montag bis Freitag) eine Aufgabe hat, wann die Beteiligten erinnert werden und wann eskaliert wird. Ein Workflow oder einer seiner Schritte kann eigene Fristen setzen; Änderungen gelten für ab jetzt erzeugte Aufgaben.',
  loading: 'Fristen werden geladen',
  sourceTenant: 'Ihre Organisation legt diese Fristen fest.',
  sourceInstallation:
    'Es gilt der Plattform-Standard: fällig nach {due} Arbeitstagen, Erinnerung {reminder} davor, Eskalation {escalation} danach.',
  fields: {
    dueWorkingDays: 'Fällig nach (Arbeitstage)',
    reminderWorkingDays: 'Erinnerung vorher (Arbeitstage)',
    escalationWorkingDays: 'Eskalation nach (Arbeitstage)',
  },
  hints: {
    dueWorkingDays: '0: keine Fristen.',
    reminderWorkingDays: '0: keine Erinnerung.',
    escalationWorkingDays: 'Nach Fristablauf; 0: keine Eskalation.',
  },
  invalidDays: 'Eine ganze Zahl von 0 bis 60.',
  reminderTooLate: 'Die Erinnerung muss vor dem Fristablauf liegen.',
  escalateTo: 'Eskalieren an',
  escalateToHint:
    'Bei Fristablauf werden die Beteiligten und ihre Vertretungen informiert; bei Eskalation zusätzlich diese Personen.',
  owner: 'Eigentümer des Dokuments',
  save: 'Speichern',
  saving: 'Wird gespeichert …',
  saved: 'Fristen gespeichert.',
  reset: 'Plattform-Standard verwenden',
  resetDone: 'Es gilt wieder der Plattform-Standard.',
  failed: 'Die Fristen wurden nicht gespeichert: {message}',
} as const
