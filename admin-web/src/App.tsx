import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Layout from './pages/Layout';
import LoginPage from './pages/LoginPage';
import OverviewPage from './pages/OverviewPage';
import UsersPage from './pages/UsersPage';
import AuditPage from './pages/AuditPage';
import ConfigPage from './pages/ConfigPage';
import FaresPage from './pages/FaresPage';

/** Blocks the shell until boot completes, then gates on auth state. */
function RequireAuth({ children }: { children: ReactNode }) {
  const { user, booted } = useAuth();
  const location = useLocation();
  if (!booted) return <p className="muted" role="status">Restoring session…</p>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

function LoginPageOrRedirect() {
  const { user, booted } = useAuth();
  if (!booted) return <p className="muted" role="status">Restoring session…</p>;
  if (user) return <Navigate to="/" replace />;
  return <LoginPage />;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPageOrRedirect />} />
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route path="/" element={<OverviewPage />} />
            <Route path="/users" element={<UsersPage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/config" element={<ConfigPage />} />
            <Route path="/fares" element={<FaresPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
