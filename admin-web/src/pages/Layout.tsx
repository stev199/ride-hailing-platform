import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { applyLang, i18n, LANGS } from '../i18n';
import { can, permissionsOf, Perm } from '../rbac/permissions';

/** Sidebar entries; each gated by the backend permission that guards its API. */
interface NavItem {
  to: string;
  labelKey: string;
  perm: Perm;
}

const NAV: NavItem[] = [
  { to: '/', labelKey: 'nav.overview', perm: Perm.USERS_READ }, // any admin perm grants shell access
  { to: '/users', labelKey: 'nav.users', perm: Perm.USERS_READ },
  { to: '/audit', labelKey: 'nav.audit', perm: Perm.AUDIT_READ },
  { to: '/config', labelKey: 'nav.config', perm: Perm.CONFIG_READ },
  { to: '/fares', labelKey: 'nav.fares', perm: Perm.CONFIG_READ },
  { to: '/trips', labelKey: 'nav.trips', perm: Perm.TRIPS_READ },
  { to: '/wallet', labelKey: 'nav.wallet', perm: Perm.MONEY_READ },
  { to: '/verification', labelKey: 'nav.verification', perm: Perm.VERIFICATION_READ },
  { to: '/live', labelKey: 'nav.live', perm: Perm.TRIPS_READ },
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
  const { t } = useTranslation();
  const currentLang = i18n.language;

  if (!user) return null; // RequireAuth wrapper guarantees a user; type-narrowing guard.

  const visibleNav = NAV.filter((item, i) => i === 0 || can(user.role, item.perm));
  const perms = permissionsOf(user.role);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">{t('brand')}</div>
        <nav aria-label={t('nav.sectionsAria')}>
          {visibleNav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => (isActive ? 'active' : undefined)}
            >
              {t(item.labelKey)}
            </NavLink>
          ))}
        </nav>
        <div className="muted" style={{ fontSize: 12 }}>v0.1 (P1-4)</div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button onClick={toggleTheme} aria-label={t('theme.ariaToggle')} data-testid="theme-toggle">
            {theme === 'dark' ? t('theme.toLight') : t('theme.toDark')}
          </button>
          <select
            aria-label={t('lang.aria')}
            value={currentLang}
            onChange={(e) => {
              applyLang(e.target.value);
              void i18n.changeLanguage(e.target.value);
            }}
            data-testid="lang-select"
          >
            {LANGS.map((l) => (
              <option key={l} value={l}>
                {t(`lang.${l}`)}
              </option>
            ))}
          </select>
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
              {t('auth.logout')}
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
