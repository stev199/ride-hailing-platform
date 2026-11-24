import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { can, permissionsOf, Perm } from '../rbac/permissions';

/** Sidebar entries; each gated by the backend permission that guards its API. */
interface NavItem {
  to: string;
  label: string;
  perm: Perm;
}

const NAV: NavItem[] = [
  { to: '/', label: 'Overview', perm: Perm.USERS_READ }, // any admin perm grants shell access; overview itself is account info
  { to: '/users', label: 'Users', perm: Perm.USERS_READ },
  { to: '/audit', label: 'Audit log', perm: Perm.AUDIT_READ },
  { to: '/config', label: 'Config center', perm: Perm.CONFIG_READ },
];

export function useTheme(): [string, () => void] {
  const [theme, setTheme] = useState<string>(() => localStorage.getItem('admin.theme') ?? 'light');
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('admin.theme', theme);
  }, [theme]);
  return [theme, () => setTheme(theme === 'dark' ? 'light' : 'dark')];
}

export default function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [theme, toggleTheme] = useTheme();

  if (!user) return null; // RequireAuth wrapper guarantees a user; type-narrowing guard.

  const visibleNav = NAV.filter((item, i) => i === 0 || can(user.role, item.perm));
  const perms = permissionsOf(user.role);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">Ride-hailing Admin</div>
        <nav aria-label="Admin sections">
          {visibleNav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => (isActive ? 'active' : undefined)}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="muted" style={{ fontSize: 12 }}>v0.1 (P1-4)</div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button onClick={toggleTheme} aria-label="Toggle color theme" data-testid="theme-toggle">
            {theme === 'dark' ? 'Light' : 'Dark'} mode
          </button>
          <div className="who">
            <span data-testid="display-name">{user.displayName}</span>
            <span className="badge role" data-testid="role-badge">{user.role}</span>
            <button
              onClick={async () => {
                await logout();
                navigate('/login');
              }}
              data-testid="logout"
            >
              Log out
            </button>
          </div>
        </header>
        <main className="content">
          <Outlet context={{ perms }} />
        </main>
      </div>
    </div>
  );
}
