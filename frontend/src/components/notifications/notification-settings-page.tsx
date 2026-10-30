import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  notificationCategories,
  notificationKeys,
  notificationPreferencesQuery,
  saveNotificationPreferences,
  type NotificationCategory,
  type NotificationPreferences,
} from '@/api/notifications'
import { errorText } from '@/components/shared/error-messages'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

type LanguageChoice = '' | 'de' | 'en'

function languageName(code: string): string {
  return code === 'en'
    ? t('notifications.settings.languages.en')
    : t('notifications.settings.languages.de')
}

/**
 * Notification settings of the signed-in user (DMS-130): the language of notifications (or the
 * organization's) and, per category, whether notifications are also sent by email. In-app
 * notifications are always on.
 */
export function NotificationSettingsPage() {
  const client = useApiClient()
  const preferences = useQuery(notificationPreferencesQuery(client))
  return (
    <div className="max-w-2xl space-y-6">
      <PageHeader
        title={t('notifications.settings.title')}
        documentTitle={t('notifications.settings.documentTitle')}
        description={t('notifications.settings.description')}
      />
      {preferences.isPending ? (
        <LoadingState label={t('notifications.loading')} />
      ) : preferences.error ? (
        <ErrorState error={preferences.error} onRetry={() => void preferences.refetch()} />
      ) : (
        <SettingsForm initial={preferences.data} />
      )}
    </div>
  )
}

function SettingsForm({ initial }: { initial: NotificationPreferences }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [language, setLanguage] = useState<LanguageChoice>(
    initial.language === 'de' || initial.language === 'en' ? initial.language : '',
  )
  const [email, setEmail] = useState<Record<string, boolean>>(() =>
    Object.fromEntries((initial.categories ?? []).map((c) => [c.category, c.email])),
  )
  const save = useMutation({
    mutationFn: () => saveNotificationPreferences(client, { language, email }),
    onSuccess: (saved) => {
      queryClient.setQueryData(notificationKeys.preferences, saved)
      toast(t('notifications.settings.saved'))
    },
    onError: (error) => {
      toast(t('notifications.settings.failed', { message: errorText(error) }), 'error')
    },
  })
  const defaults = new Map((initial.categories ?? []).map((c) => [c.category, c.emailByDefault]))
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate()
      }}
      className="space-y-8"
    >
      <div className="space-y-2">
        <Label htmlFor={`${id}-language`}>{t('notifications.settings.language')}</Label>
        <select
          id={`${id}-language`}
          aria-describedby={`${id}-language-hint`}
          className="h-9 w-full max-w-xs rounded-md border bg-background px-3 text-sm"
          value={language}
          onChange={(e) => {
            setLanguage(e.target.value as LanguageChoice)
          }}
        >
          <option value="">
            {t('notifications.settings.languageTenant', {
              language: languageName(initial.tenantLanguage),
            })}
          </option>
          <option value="de">{t('notifications.settings.languages.de')}</option>
          <option value="en">{t('notifications.settings.languages.en')}</option>
        </select>
        <p id={`${id}-language-hint`} className="text-sm text-muted-foreground">
          {t('notifications.settings.languageHint')}
        </p>
      </div>
      <fieldset className="space-y-3" aria-describedby={`${id}-email-hint`}>
        <legend className="text-sm font-medium">{t('notifications.settings.email')}</legend>
        <p id={`${id}-email-hint`} className="text-sm text-muted-foreground">
          {initial.emailAvailable
            ? t('notifications.settings.emailHint')
            : t('notifications.settings.emailUnavailable')}
        </p>
        {notificationCategories.map((category: NotificationCategory) => (
          <div key={category} className="flex items-start gap-3">
            <input
              id={`${id}-${category}`}
              type="checkbox"
              className="mt-1 size-4"
              disabled={!initial.emailAvailable}
              checked={email[category] ?? defaults.get(category) ?? false}
              aria-describedby={`${id}-${category}-hint`}
              onChange={(e) => {
                setEmail((current) => ({ ...current, [category]: e.target.checked }))
              }}
            />
            <div>
              <Label htmlFor={`${id}-${category}`}>
                {t(`notifications.categories.${category}`)}
              </Label>
              <p id={`${id}-${category}-hint`} className="text-sm text-muted-foreground">
                {t(`notifications.settings.hints.${category}`)}{' '}
                {t('notifications.settings.emailDefault', {
                  state: defaults.get(category)
                    ? t('notifications.settings.on')
                    : t('notifications.settings.off'),
                })}
              </p>
            </div>
          </div>
        ))}
      </fieldset>
      <Button type="submit" disabled={save.isPending}>
        {save.isPending ? t('notifications.settings.saving') : t('notifications.settings.save')}
      </Button>
    </form>
  )
}
