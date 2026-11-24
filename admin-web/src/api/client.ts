import type { ApiError } from './types';

/**
 * Token storage policy (P1-4, D67):
 * - access token: memory only (module state) + sessionStorage mirror so a reload
 *   re-hydrates without waiting for a refresh round-trip;
 * - refresh token: localStorage (the admin session survives a browser restart by
 *   design; logout clears it and the backend revokes the family).
 * XSS is the threat - React escapes by default and we render no user HTML.
 */
const REFRESH_KEY = 'admin.refreshToken';
const ACCESS_KEY = 'admin.accessToken';

export const tokenStore = {
  getRefresh(): string | null {
    return localStorage.getItem(REFRESH_KEY);
  },
  setRefresh(v: string): void {
    localStorage.setItem(REFRESH_KEY, v);
  },
  clearRefresh(): void {
    localStorage.removeItem(REFRESH_KEY);
  },
  getAccess(): string | null {
    return sessionStorage.getItem(ACCESS_KEY);
  },
  setAccess(v: string): void {
    sessionStorage.setItem(ACCESS_KEY, v);
  },
  clearAccess(): void {
    sessionStorage.removeItem(ACCESS_KEY);
  },
};

export interface ApiClientOptions {
  baseUrl: string;
  /** Called when a request comes back 401 even after one refresh attempt. */
  onAuthLost: () => void;
  /** Refresh call factory to break the import cycle (AuthContext supplies it). */
  refresh: () => Promise<boolean>;
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly onAuthLost: () => void;
  private readonly refresh: () => Promise<boolean>;
  private refreshInFlight: Promise<boolean> | null = null;

  constructor(opts: ApiClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/$/, '');
    this.onAuthLost = opts.onAuthLost;
    this.refresh = opts.refresh;
  }

  /** Raw fetch with bearer auth and ONE shared refresh retry on 401. */
  async request<T>(path: string, init?: RequestInit): Promise<T> {
    const doFetch = (token: string | null): Promise<Response> =>
      fetch(`${this.baseUrl}${path}`, {
        ...init,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...init?.headers,
        },
      });

    let res = await doFetch(tokenStore.getAccess());
    if (res.status === 401 && tokenStore.getRefresh() && !path.startsWith('/v1/auth/')) {
      // Single-flight refresh: concurrent 401s share one rotation call.
      this.refreshInFlight ??= this.refresh().finally(() => {
        this.refreshInFlight = null;
      });
      const ok = await this.refreshInFlight;
      if (ok) {
        res = await doFetch(tokenStore.getAccess());
      }
    }
    if (res.status === 401) {
      this.onAuthLost();
      throw await this.errorOf(res);
    }
    if (!res.ok) {
      throw await this.errorOf(res);
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  private async errorOf(res: Response): Promise<ApiError> {
    try {
      const body = (await res.json()) as ApiError;
      if (body && typeof body.code === 'string') return body;
    } catch {
      // fall through to generic
    }
    return { code: 'http_error', message: `HTTP ${res.status}`, traceId: null };
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'GET' });
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) });
  }
}
