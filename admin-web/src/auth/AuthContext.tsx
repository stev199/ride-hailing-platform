import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { AuthSuccess, TokenPair, User } from '../api/types';
import { ApiClient, tokenStore } from '../api/client';

export interface AuthState {
  /** null while the initial refresh probe is in flight. */
  user: User | null;
  booted: boolean;
  login: (identifier: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** One refresh-token rotation; shared by boot and 401-retry paths. */
  refresh: () => Promise<boolean>;
  api: ApiClient;
}

const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}

export const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [booted, setBooted] = useState(false);
  const refreshInFlight = useRef<Promise<boolean> | null>(null);

  const storeTokens = useCallback((tokens: TokenPair) => {
    tokenStore.setAccess(tokens.accessToken);
    tokenStore.setRefresh(tokens.refreshToken);
  }, []);

  /** POST /v1/auth/refresh - rotates the family; reuse of a consumed token revokes it. */
  const refresh = useCallback((): Promise<boolean> => {
    refreshInFlight.current ??= (async () => {
      const rt = tokenStore.getRefresh();
      if (!rt) return false;
      try {
        const res = await fetch(`${API_BASE}/v1/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: rt }),
        });
        if (!res.ok) {
          tokenStore.clearRefresh();
          tokenStore.clearAccess();
          return false;
        }
        const body = (await res.json()) as { tokens: TokenPair };
        storeTokens(body.tokens);
        return true;
      } catch {
        return false;
      } finally {
        refreshInFlight.current = null;
      }
    })();
    return refreshInFlight.current;
  }, [storeTokens]);

  const api = useMemo(
    () =>
      new ApiClient({
        baseUrl: API_BASE,
        onAuthLost: () => {
          setUser(null);
          tokenStore.clearAccess();
          tokenStore.clearRefresh();
        },
        refresh,
      }),
    [refresh],
  );

  // Boot: with a stored refresh token, rotate once and re-hydrate the profile.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (tokenStore.getRefresh()) {
        const ok = await refresh();
        if (ok && !cancelled) {
          try {
            const me = await api.get<User>('/v1/me');
            setUser(me);
          } catch {
            tokenStore.clearAccess();
            tokenStore.clearRefresh();
          }
        }
      }
      if (!cancelled) setBooted(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [api, refresh]);

  const login = useCallback(
    async (identifier: string, password: string) => {
      const res = await api.post<AuthSuccess>('/v1/auth/login/password', { identifier, password });
      storeTokens(res.tokens);
      setUser(res.user);
    },
    [api, storeTokens],
  );

  const logout = useCallback(async () => {
    const rt = tokenStore.getRefresh();
    try {
      if (rt) await api.post('/v1/auth/logout', { refreshToken: rt });
    } catch {
      // Best-effort revoke: network failure must never leave local state stuck.
    } finally {
      tokenStore.clearAccess();
      tokenStore.clearRefresh();
      setUser(null);
    }
  }, [api]);

  const value = useMemo<AuthState>(
    () => ({ user, booted, login, logout, refresh, api }),
    [user, booted, login, logout, refresh, api],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
