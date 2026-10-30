import { Link, Outlet, useNavigate, useRouter, useRouterState } from '@tanstack/react-router'
import {
  Archive,
  BellRing,
  CheckSquare,
  Menu,
  FileText,
  LayoutDashboard,
  LayoutTemplate,
  Library,
  LogOut,
  Languages,
  Moon,
  Scale,
  Search,
  Settings,
  ShieldCheck,
  Sun,
  UserRoundCheck,
} from 'lucide-react'
import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { useAuth } from 'react-oidc-context'
import { canManageLegalHolds } from '@/api/legal-holds'
import { canManageRetention } from '@/api/retention'
import { hasRole, useMe, type BusinessRole, type Me } from '@/api/me'
import { displayName } from '@/auth/profile'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Toaster } from '@/components/shared/toaster'
import { NotificationBell } from '@/components/notifications/notification-bell'
import { useOpenTaskCount } from '@/components/tasks/use-open-task-count'
import { Input } from '@/components/ui/input'
import { currentTheme, setTheme, type Theme } from '@/lib/theme'
import { LOCALES, setLocale, t, useLocale, type Locale } from '@/i18n'
import type { Messages } from '@/i18n/locales/en'

/**
 * Navigation entries; those with `role` are shown only to holders of that business role, those with
 * `allowed` only to users it accepts.
 */
const navItems: readonly {
  to:
    | '/'
    | '/documents'
    | '/library'
    | '/search'
    | '/tasks'
    | '/templates'
    | '/compliance'
    | '/legal-holds'
    | '/retention'
    | '/admin'
  label: keyof Messages['shell']['nav']
  icon: typeof LayoutDashboard
  role?: BusinessRole
  allowed?: (me: Me | undefined) => boolean
}[] = [
  { to: '/', label: 'home', icon: LayoutDashboard },
  { to: '/documents', label: 'documents', icon: FileText },
  { to: '/library', label: 'library', icon: Library },
  { to: '/search', label: 'search', icon: Search },
  { to: '/tasks', label: 'tasks', icon: CheckSquare },
  { to: '/templates', label: 'templates', icon: LayoutTemplate, role: 'TemplateDesigner' },
  { to: '/compliance', label: 'compliance', icon: ShieldCheck, role: 'ComplianceOfficer' },
  // Records managers for the whole tenant: the only ones the legal hold endpoints accept (ADR 0047).
  { to: '/legal-holds', label: 'legalHolds', icon: Scale, allowed: canManageLegalHolds },
  // Retention and destruction (DMS-82, ADR 0054): the same records managers.
  { to: '/retention', label: 'retention', icon: Archive, allowed: canManageRetention },
  { to: '/admin', label: 'admin', icon: Settings, role: 'TenantAdmin' },
]

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  return parts
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('')
}

function ThemeToggle() {
  const [theme, setState] = useState<Theme>(currentTheme)
  const next: Theme = theme === 'dark' ? 'light' : 'dark'
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={next === 'dark' ? t('shell.switchToDark') : t('shell.switchToLight')}
      onClick={() => {
        setTheme(next)
        setState(next)
      }}
    >
      {theme === 'dark' ? <Sun /> : <Moon />}
    </Button>
  )
}

function UserMenu() {
  const auth = useAuth()
  const profile = auth.user?.profile
  const name = displayName(profile) ?? t('shell.signedInUser')
  const locale = useLocale()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="gap-2 px-2" aria-label={t('shell.userMenu')}>
          <Avatar className="size-7">
            <AvatarFallback>{initials(name) || '?'}</AvatarFallback>
          </Avatar>
          <span className="hidden text-sm sm:inline">{name}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel>
          <div className="font-medium text-foreground">{name}</div>
          {profile?.email && (
            <div className="text-xs font-normal text-muted-foreground">{profile.email}</div>
          )}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link to="/deputies">
            <UserRoundCheck />
            {t('shell.deputies')}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <Link to="/settings/notifications">
            <BellRing />
            {t('notifications.settingsLink')}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuLabel className="flex items-center gap-2 text-xs font-normal text-muted-foreground">
          <Languages aria-hidden="true" className="size-4" />
          {t('shell.language')}
        </DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={locale}
          onValueChange={(value) => {
            setLocale(value as Locale)
          }}
        >
          {LOCALES.map((l) => (
            <DropdownMenuRadioItem key={l} value={l} lang={l}>
              {t(`shell.languages.${l}`)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void auth.signoutRedirect()}>
          <LogOut />
          {t('shell.signOut')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const navLinkClass =
  'group flex min-h-9 items-center gap-2 rounded-md px-3 py-2 text-sm whitespace-nowrap hover:bg-sidebar-accent hover:text-sidebar-accent-foreground data-[status=active]:bg-sidebar-primary data-[status=active]:font-medium data-[status=active]:text-sidebar-primary-foreground'

/** The open-task count next to "Tasks"; screen readers get it as description of the link. */
function TaskCount({ count, id }: { count: number | undefined; id: string }) {
  if (!count) return null
  return (
    <>
      <span
        aria-hidden="true"
        className="ml-auto rounded-full bg-sidebar-accent px-1.5 text-xs font-medium text-sidebar-accent-foreground tabular-nums group-data-[status=active]:bg-sidebar-primary-foreground group-data-[status=active]:text-sidebar-primary"
      >
        {count}
      </span>
      <span id={id} className="sr-only">
        {t('shell.openTasks', { count })}
      </span>
    </>
  )
}

type NavItem = (typeof navItems)[number]

/** Sidebar navigation from md; links of nested pages stay highlighted (`/documents/...`). */
function SidebarNav({ items, taskCount }: { items: readonly NavItem[]; taskCount?: number }) {
  const countId = useId()
  return (
    <nav
      aria-label={t('shell.mainNav')}
      className="hidden border-r bg-sidebar text-sidebar-foreground md:block md:w-56 md:shrink-0"
    >
      {/* Stays in view below the header on long pages and scrolls on its own if needed. */}
      <ul className="sticky top-14 flex max-h-[calc(100svh-3.5rem)] flex-col gap-1 overflow-y-auto p-2">
        {items.map(({ to, label, icon: Icon }) => (
          <li key={to}>
            <Link
              to={to}
              activeOptions={{ exact: to === '/', includeSearch: false }}
              aria-describedby={to === '/tasks' && taskCount ? countId : undefined}
              className={navLinkClass}
            >
              <Icon aria-hidden="true" className="size-4" />
              {t(`shell.nav.${label}`)}
              {to === '/tasks' && <TaskCount count={taskCount} id={countId} />}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}

/** Navigation below md: a menu button instead of the sidebar. */
function MobileNav({ items, taskCount }: { items: readonly NavItem[]; taskCount?: number }) {
  const countId = useId()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="md:hidden" aria-label={t('shell.openMenu')}>
          <Menu />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-56" aria-label={t('shell.mainNav')}>
        {items.map(({ to, label, icon: Icon }) => (
          <DropdownMenuItem key={to} asChild>
            <Link
              to={to}
              activeOptions={{ exact: to === '/', includeSearch: false }}
              aria-describedby={to === '/tasks' && taskCount ? countId : undefined}
              className="group min-h-9 data-[status=active]:font-semibold"
            >
              <Icon aria-hidden="true" />
              {t(`shell.nav.${label}`)}
              {to === '/tasks' && <TaskCount count={taskCount} id={countId} />}
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** Search field in the header; submits to the search page. Hidden there, it has its own field. */
function HeaderSearch() {
  const navigate = useNavigate()
  const [text, setText] = useState('')
  return (
    <form
      role="search"
      className="hidden w-full max-w-xs sm:block"
      onSubmit={(e) => {
        e.preventDefault()
        const q = text.trim()
        void navigate({ to: '/search', search: q ? { q } : {} })
        setText('')
      }}
    >
      <div className="relative">
        <Search
          aria-hidden="true"
          className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          type="search"
          aria-label={t('shell.search')}
          placeholder={t('shell.searchPlaceholder')}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
          }}
          className="pl-8"
        />
      </div>
    </form>
  )
}

/**
 * After navigating to another page, moves focus to the page heading (or the main region), so
 * screen-reader users hear where they are and keyboard users start at the content.
 */
function useFocusOnNavigation(main: RefObject<HTMLElement | null>) {
  const router = useRouter()
  useEffect(
    () =>
      router.subscribe('onResolved', (event) => {
        if (!event.fromLocation || !event.pathChanged) return
        requestAnimationFrame(() => {
          const target =
            main.current?.querySelector<HTMLElement>('[data-page-heading]') ?? main.current
          target?.focus({ preventScroll: true })
        })
      }),
    [router, main],
  )
}

/** Layout for signed-in pages: header, sidebar navigation and the routed content. */
export function AppShell() {
  const me = useMe()
  const taskCount = useOpenTaskCount()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const main = useRef<HTMLElement>(null)
  useFocusOnNavigation(main)
  const visible = navItems.filter(
    (item) =>
      (!item.role || hasRole(me.data, item.role)) && (!item.allowed || item.allowed(me.data)),
  )
  return (
    <div className="flex min-h-svh flex-col">
      <a
        href="#main-content"
        className="sr-only rounded-md bg-background px-3 py-2 text-sm font-medium ring-3 ring-ring focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50"
        onClick={(e) => {
          e.preventDefault()
          main.current?.focus()
        }}
      >
        {t('shell.skipToContent')}
      </a>
      <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b bg-background/95 px-4 backdrop-blur">
        <MobileNav items={visible} taskCount={taskCount} />
        <Link
          to="/"
          className="shrink-0 rounded-md font-heading text-lg font-semibold tracking-tight"
        >
          {t('common.product')}
        </Link>
        <div className="flex flex-1 justify-end sm:justify-center">
          {pathname !== '/search' && <HeaderSearch />}
        </div>
        <div className="flex items-center gap-1">
          <NotificationBell />
          <ThemeToggle />
          <UserMenu />
        </div>
      </header>
      <div className="flex flex-1">
        <SidebarNav items={visible} taskCount={taskCount} />
        <main
          ref={main}
          id="main-content"
          tabIndex={-1}
          className="min-w-0 flex-1 p-4 focus:outline-none md:p-8"
        >
          <div className="mx-auto w-full max-w-7xl">
            <Outlet />
          </div>
        </main>
      </div>
      <Toaster />
    </div>
  )
}
