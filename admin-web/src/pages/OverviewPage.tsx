import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { ALL_PERMS, can } from '../rbac/permissions';

/** Account overview: identity + the exact permission set this role carries. */
export default function OverviewPage() {
  const { user } = useAuth();
  const { t } = useTranslation();
  if (!user) return null;

  return (
    <div>
      <h1 className="page-title">{t('overview.title')}</h1>
      <div className="card">
        <table className="data" aria-label={t('overview.tableAria')}>
          <tbody>
            <tr><th scope="row">{t('overview.displayName')}</th><td data-testid="ov-name">{user.displayName}</td></tr>
            <tr><th scope="row">{t('overview.role')}</th><td>{user.role}</td></tr>
            <tr><th scope="row">{t('overview.status')}</th><td>{user.status}</td></tr>
            <tr><th scope="row">{t('overview.locale')}</th><td>{user.locale}</td></tr>
            <tr><th scope="row">{t('overview.phone')}</th><td>{user.phone ?? '—'}</td></tr>
            <tr><th scope="row">{t('overview.email')}</th><td>{user.email ?? '—'}</td></tr>
          </tbody>
        </table>
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 14 }}>{t('overview.granted', { count: permissionsCount(user.role), total: ALL_PERMS.length })}</h2>
        <div className="perm-chips" data-testid="perm-chips">
          {ALL_PERMS.map((p) => (
            <span key={p} className="badge" style={{ opacity: can(user.role, p) ? 1 : 0.35 }}>
              {p}{can(user.role, p) ? '' : ' (no)'}
            </span>
          ))}
        </div>
        <p className="muted" style={{ fontSize: 12 }}>{t('overview.rbacNote')}</p>
      </div>
    </div>
  );
}

function permissionsCount(role: Parameters<typeof can>[0]): number {
  return ALL_PERMS.filter((p) => can(role, p)).length;
}
