import { LoadingState } from '@/components/shared/states'
import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { designerFieldsQuery, type TemplateVersion } from '@/api/template-admin'
import type { FieldType } from '@/api/templates'
import { Badge } from '@/components/ui/badge'
import { t } from '@/i18n'
import { errorMessage } from './template-lifecycle'

function typeLabel(fieldType: FieldType): string {
  return fieldType.type
}

/** The fields of the version's class profile: those the template uses first (designer endpoint). */
export function FieldsPanel({ version }: { version: TemplateVersion }) {
  const client = useApiClient()
  const fields = useQuery(designerFieldsQuery(client, version.templateId, version.version))

  if (fields.isPending) return <LoadingState label={t('templates.fields.loading')} rows={3} />
  if (fields.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        {t('templates.fields.failed', { message: errorMessage(fields.error) })}
      </p>
    )
  }
  const references = fields.data.references ?? []
  return (
    <div className="space-y-3">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">{t('templates.fields.caption')}</caption>
          <thead className="text-left text-xs text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="py-1 pr-3 font-medium">
                {t('templates.fields.field')}
              </th>
              <th scope="col" className="py-1 pr-3 font-medium">
                {t('templates.fields.key')}
              </th>
              <th scope="col" className="py-1 pr-3 font-medium">
                {t('templates.fields.type')}
              </th>
              <th scope="col" className="py-1 font-medium">
                {t('templates.fields.notes')}
              </th>
            </tr>
          </thead>
          <tbody>
            {(fields.data.fields ?? []).map((f) => (
              <tr key={f.key} className="border-b last:border-0">
                <td className="py-1 pr-3">{f.label}</td>
                <td className="py-1 pr-3 font-mono text-xs">{f.key}</td>
                <td className="py-1 pr-3 text-xs">
                  {typeLabel(f.fieldType)}
                  {f.multiple ? t('templates.fields.multiple') : ''}
                </td>
                <td className="flex flex-wrap gap-1 py-1">
                  {f.usedInTemplate && (
                    <Badge variant="secondary">{t('templates.fields.used')}</Badge>
                  )}
                  {f.required && <Badge variant="outline">{t('templates.fields.required')}</Badge>}
                  {f.reference && (
                    <Badge variant="outline">
                      {t('templates.fields.from', { source: f.reference.reference })}
                    </Badge>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {references.length > 0 && (
        <p className="text-xs text-muted-foreground">
          {t('templates.fields.references', {
            list: references.map((r) => `${r.label} (${r.source})`).join(', '),
          })}
        </p>
      )}
    </div>
  )
}
