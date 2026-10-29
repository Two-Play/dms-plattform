import { LoadingState } from '@/components/shared/states'
import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { designerFieldsQuery, type TemplateVersion } from '@/api/template-admin'
import type { FieldType } from '@/api/templates'
import { Badge } from '@/components/ui/badge'
import { errorMessage } from './template-lifecycle'

function typeLabel(t: FieldType): string {
  return t.type
}

/** The fields of the version's class profile: those the template uses first (designer endpoint). */
export function FieldsPanel({ version }: { version: TemplateVersion }) {
  const client = useApiClient()
  const fields = useQuery(designerFieldsQuery(client, version.templateId, version.version))

  if (fields.isPending) return <LoadingState label="Loading the fields" rows={3} />
  if (fields.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        The fields cannot be listed: {errorMessage(fields.error)}
      </p>
    )
  }
  const references = fields.data.references ?? []
  return (
    <div className="space-y-3">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">Fields</caption>
          <thead className="text-left text-xs text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="py-1 pr-3 font-medium">
                Field
              </th>
              <th scope="col" className="py-1 pr-3 font-medium">
                Key
              </th>
              <th scope="col" className="py-1 pr-3 font-medium">
                Type
              </th>
              <th scope="col" className="py-1 font-medium">
                Notes
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
                  {f.multiple ? ' (multiple)' : ''}
                </td>
                <td className="flex flex-wrap gap-1 py-1">
                  {f.usedInTemplate && <Badge variant="secondary">Used in template</Badge>}
                  {f.required && <Badge variant="outline">Required for approval</Badge>}
                  {f.reference && <Badge variant="outline">From {f.reference.reference}</Badge>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {references.length > 0 && (
        <p className="text-xs text-muted-foreground">
          Reference fields: {references.map((r) => `${r.label} (${r.source})`).join(', ')}
        </p>
      )}
    </div>
  )
}
