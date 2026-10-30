import { type Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/word'

export const word: Translation<typeof en.word> = {
  file: {
    title: 'Dokumentdatei',
    description:
      'Die Datei ist der Inhalt dieser Version; ihre Prüfsumme wird mit der Freigabe gebunden.',
    descriptionDraft:
      'Die Datei ist der Inhalt dieser Version. Solange die Version ein Entwurf ist, kann sie durch eine überarbeitete Datei ersetzt werden.',
    none: 'Noch keine Datei. Laden Sie das Word-Dokument (oder eine andere Office-Datei oder ein PDF) hoch, das den Inhalt dieser Version bildet.',
    upload: 'Datei hochladen …',
    replace: 'Datei ersetzen …',
    uploading: 'Wird hochgeladen: {percent} %',
    uploaded: 'Datei hochgeladen.',
    uploadFailed: 'Das Hochladen ist fehlgeschlagen: {message}',
    accepted: 'Word, Excel, PowerPoint, OpenDocument, RTF, Text und PDF.',
    notConvertible: 'Dateien vom Typ .{extension} können nicht als PDF/A archiviert werden.',
    name: 'Datei',
    size: 'Größe',
    checksum: 'SHA-256 {sha}',
    uploadedBy: 'Hochgeladen am {date} von',
    takenOver: 'Aus der vorherigen Version übernommen.',
    downloadOriginal: 'Original herunterladen',
    downloadWord: 'Word herunterladen (befüllt)',
    downloadFilled: 'Befüllte Datei herunterladen',
    downloadFailed: 'Das Herunterladen ist fehlgeschlagen: {message}',
    preview: 'Vorschau (PDF)',
    previewLoad: 'Vorschau anzeigen',
    previewLoading: 'Dokument wird umgewandelt …',
    previewFailed: 'Die Vorschau ist nicht verfügbar: {message}',
    previewDraft:
      'Aus dem zuletzt gespeicherten Stand mit befüllten Feldern erzeugt; als „Entwurf – nicht gültig“ markiert, nicht archiviert.',
    previewApproved: 'Die archivierte PDF/A-Fassung der freigegebenen Version.',
    previewRegion: 'Vorschau der Version {label}',
  },
  fields: {
    title: 'Felder des Word-Dokuments',
    description:
      'Automatische Felder füllt die Plattform; die manuellen Felder tragen Sie hier ein. Die Werte werden in das Word-Dokument und sein PDF übernommen.',
    none: 'Das Word-Dokument enthält keine Felder.',
    loading: 'Felder werden geladen',
    automatic: 'automatisch',
    manual: 'manuell',
    required: 'Pflichtfeld',
    notSet: 'noch nicht gesetzt',
    save: 'Feldwerte speichern',
    saving: 'Wird gespeichert …',
    saved: 'Feldwerte gespeichert.',
    failed: 'Die Feldwerte konnten nicht gespeichert werden: {message}',
    readOnly: 'Die Feldwerte sind mit der Version eingefroren.',
    yes: 'Ja',
    no: 'Nein',
    choose: 'Bitte wählen …',
    noRows: 'Noch keine Zeilen.',
    addRow: 'Zeile hinzufügen',
    removeRow: 'Zeile {row} entfernen',
    cell: '{column}, Zeile {row}',
    actions: 'Aktionen',
    warning: 'Wird nicht so befüllt, wie die Datei es zeigt: {message}',
  },
  help: {
    title: 'So kennzeichnen Sie Felder in Word',
    controls:
      'Fügen Sie Inhaltssteuerelemente ein (Registerkarte Entwicklertools → Steuerelemente, Nur-Text). Das Tag ist der Feldschlüssel, der Titel die Beschriftung.',
    automatic:
      'Das Tag dms:<Schlüssel> füllt die Plattform, z. B. dms:documentNumber, dms:version, dms:title, dms:author, dms:owner, dms:issuingUnit, dms:validFrom, dms:validUntil, dms:status, dms:approvedAt, dms:approvedBy oder jedes Metadatenfeld der Klasse (dms:<Feldschlüssel>).',
    manual:
      'Jedes andere Tag, z. B. customer, ist ein manuelles Feld, das die Autorin oder der Autor ausfüllt.',
    types:
      'Kontrollkästchen, Datumsauswahl und Dropdownlisten werden im Formular zu einem Kontrollkästchen, einem Datum und einer Auswahl der Listeneinträge; die Datumsauswahl druckt das Datum in ihrem eigenen Format.',
    repeating:
      'Ein Wiederholungsabschnitt (z. B. um eine Tabellenzeile) mit Tag wird zu einer Liste von Zeilen: Seine Inhaltssteuerelemente sind die Spalten, und der Abschnitt wird für jede eingetragene Zeile wiederholt. Verschachtelte Wiederholungsabschnitte werden nicht unterstützt und gemeldet.',
    placeholders:
      'Alternativ {manual} oder {automatic} in den Text schreiben; auch ein durch Formatierung geteilter Platzhalter wird gefunden und übernimmt die Formatierung seines ersten Teils.',
    office:
      'Excel- (.xlsx) und PowerPoint-Dateien (.pptx): Platzhalter in Zellen und auf Folien werden als Text befüllt.',
    values:
      'Beim Befüllen bleiben die Schlüssel erhalten: Inhaltssteuerelemente behalten Tag und Titel, ein befüllter Platzhalter wird zu einem Nur-Text-Steuerelement mit seinem Schlüssel als Tag, und jeder Wert wird zusätzlich im Dokument gespeichert (benutzerdefinierte Dokumenteigenschaften dms.<Schlüssel> und ein benutzerdefinierter XML-Teil, an den die Nur-Text-Steuerelemente gebunden sind). Ein befülltes Dokument, das erneut hochgeladen wird, bringt seine Werte wieder mit.',
  },
  templates: {
    badge: 'Word',
    newTitle: 'Neue Word-Vorlage',
    newText:
      'Eine Word-Datei mit Feldern wird zur Vorlage: Autorinnen und Autoren erstellen Dokumente daraus, die Plattform füllt die automatischen Felder.',
    name: 'Name',
    description: 'Beschreibung (optional)',
    classOf: 'Dokumentklasse',
    chooseClass: 'Dokumentklasse wählen …',
    classesFailed: 'Die Dokumentklassen konnten nicht geladen werden: {message}',
    file: 'Word-Datei (.docx)',
    create: 'Word-Vorlage anlegen',
    creating: 'Wird angelegt …',
    createFailed: 'Die Word-Vorlage konnte nicht angelegt werden: {message}',
    notDocx: 'Wählen Sie ein Word-Dokument (.docx).',
    panelTitle: 'Word-Datei',
    panelText:
      'Die Word-Datei dieser Version. Solange die Version ein Entwurf ist, kann sie ersetzt werden.',
    panelReadOnly: 'Die Word-Datei ist mit der Version eingefroren.',
    upload: 'Word-Datei ersetzen …',
    download: 'Word-Datei herunterladen',
    fieldsTitle: 'Felddefinitionen',
    fieldsText:
      'Die in der Word-Datei gefundenen Felder. Beschriftung, Typ, Pflicht und automatische Befüllung lassen sich im Entwurf ändern.',
    key: 'Schlüssel',
    label: 'Beschriftung',
    type: 'Typ',
    requiredColumn: 'Pflicht',
    source: 'Quelle',
    types: {
      Text: 'Text',
      Date: 'Datum',
      Number: 'Zahl',
      Checkbox: 'Kontrollkästchen',
      Choice: 'Auswahl',
      Rows: 'Zeilen (Wiederholungsabschnitt)',
    },
    options: 'Auswahlwerte (einer pro Zeile): {key}',
    optionsLabel: 'Auswahlwerte (einer pro Zeile)',
    columns: 'Spalten',
    columnOf: '{key}.{column}',
    warning: 'Wird nicht so befüllt, wie die Word-Datei es zeigt: {message}',
    sources: { Automatic: 'Automatisch', Manual: 'Manuell' },
    saveFields: 'Felddefinitionen speichern',
    fieldsSaved: 'Felddefinitionen gespeichert.',
    fieldsFailed: 'Die Felddefinitionen konnten nicht gespeichert werden: {message}',
    newVersion: 'Neue Version der Word-Vorlage',
  },
  new: {
    content: 'Inhalt',
    structured: 'Hier bearbeiten (strukturiert)',
    file: 'Datei nach dem Speichern hochladen',
    wordOption: '{name} (Word) – Version {version}',
    wordHint:
      'Das Dokument beginnt mit der Datei der Word-Vorlage. Nach dem Speichern füllen Sie auf der Dokumentseite die Felder aus und prüfen die Vorschau.',
    fileHint:
      'Nach dem Speichern laden Sie auf der Dokumentseite die Datei hoch (z. B. ein Word-Dokument).',
    uploadAside:
      'Speichern Sie zuerst den Entwurf; danach laden Sie hier die Datei hoch und füllen die Felder des Word-Dokuments aus.',
  },
}
