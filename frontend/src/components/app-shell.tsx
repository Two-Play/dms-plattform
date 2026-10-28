import { Link, Outlet } from '@tanstack/react-router'
import {
  CheckSquare,
  FileText,
  LayoutDashboard,
  LayoutTemplate,
  LogOut,
  Moon,
  Settings,
  Sun,
} from 'lucide-react'
import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { hasRole, useMe, type BusinessRole } from '@/api/me'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { currentTheme, setTheme, type Theme } from '@/lib/theme'

/** Navigation entries; those with `role` are shown only to holders of that business role. */
const navItems: readonly {
  to: '/' | '/documents' | '/tasks' | '/templates' | '/admin'
  label: string
  icon: typeof LayoutDashboard
  role?: BusinessRole
}[] = [
  { to: '/', label: 'Home', icon: LayoutDashboard },
  { to: '/documents', label: 'Documents', icon: FileText },
  { to: '/tasks', label: 'Tasks', icon: CheckSquare },
  { to: '/templates', label: 'Templates', icon: LayoutTemplate, role: 'TemplateDesigner' },
  { to: '/admin', label: 'Admin', icon: Settings, role: 'TenantAdmin' },
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
      aria-label={`Switch to ${next} mode`}
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
  const name = profile?.name ?? profile?.preferred_username ?? 'Signed-in user'

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="gap-2 px-2" aria-label="User menu">
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
        <DropdownMenuItem onSelect={() => void auth.signoutRedirect()}>
          <LogOut />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** Layout for signed-in pages: header, sidebar navigation and the routed content. */
export function AppShell() {
  const me = useMe()
  const visible = navItems.filter((item) => !item.role || hasRole(me.data, item.role))
  return (
    <div className="flex min-h-svh flex-col">
      <header className="sticky top-0 z-10 flex h-14 items-center justify-between border-b bg-background/95 px-4 backdrop-blur">
        <Link to="/" className="font-heading text-lg font-semibold tracking-tight">
          DMS Platform
        </Link>
        <div className="flex items-center gap-1">
          <ThemeToggle />
          <UserMenu />
        </div>
      </header>
      <div className="flex flex-1 flex-col md:flex-row">
        <nav
          aria-label="Main"
          className="border-b bg-sidebar text-sidebar-foreground md:w-56 md:shrink-0 md:border-r md:border-b-0"
        >
          <ul className="flex gap-1 overflow-x-auto p-2 md:flex-col">
            {visible.map(({ to, label, icon: Icon }) => (
              <li key={to}>
                <Link
                  to={to}
                  activeOptions={{ exact: to === '/' }}
                  className="flex items-center gap-2 rounded-md px-3 py-2 text-sm whitespace-nowrap hover:bg-sidebar-accent hover:text-sidebar-accent-foreground data-[status=active]:bg-sidebar-primary data-[status=active]:text-sidebar-primary-foreground"
                >
                  <Icon className="size-4" />
                  {label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
        <main className="flex-1 p-4 md:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
