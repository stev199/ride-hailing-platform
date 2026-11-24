import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, tokenStore } from '../client';

/** Minimal Response helper. */
const jsonRes = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('ApiClient auth behavior', () => {
  const onAuthLost = vi.fn<() => void>();
  let refreshMock: ReturnType<typeof vi.fn<() => Promise<boolean>>>;

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    onAuthLost.mockClear();
    refreshMock = vi.fn<() => Promise<boolean>>();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('attaches bearer header when an access token is stored', async () => {
    tokenStore.setAccess('tok-1');
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(jsonRes(200, { ok: true }));
    const client = new ApiClient({ baseUrl: 'http://x', onAuthLost, refresh: refreshMock });
    await client.get('/v1/me');
    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('http://x/v1/me');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok-1');
  });

  it('401 triggers ONE shared refresh retry then succeeds', async () => {
    tokenStore.setRefresh('rt');
    refreshMock.mockResolvedValueOnce(true);
    tokenStore.setAccess('tok-2'); // refresh() in real code rewrites the access token
    const f = globalThis.fetch as ReturnType<typeof vi.fn>;
    f.mockResolvedValueOnce(jsonRes(401, { code: 'unauthorized', message: 'x', traceId: null }));
    f.mockResolvedValueOnce(jsonRes(200, { id: 'u1' }));
    const client = new ApiClient({ baseUrl: 'http://x', onAuthLost, refresh: refreshMock });
    const res = await client.get<{ id: string }>('/v1/admin/users');
    expect(res.id).toBe('u1');
    expect(f).toHaveBeenCalledTimes(2);
    expect(onAuthLost).not.toHaveBeenCalled();
  });

  it('401 after failed refresh calls onAuthLost and throws the ApiError', async () => {
    tokenStore.setRefresh('rt');
    refreshMock.mockResolvedValueOnce(false);
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      jsonRes(401, { code: 'unauthorized', message: 'bad token', traceId: null }),
    );
    const client = new ApiClient({ baseUrl: 'http://x', onAuthLost, refresh: refreshMock });
    await expect(client.get('/v1/admin/users')).rejects.toMatchObject({ code: 'unauthorized' });
    expect(onAuthLost).toHaveBeenCalledTimes(1);
  });

  it('non-401 errors surface as ApiError without refresh attempts', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      jsonRes(403, { code: 'forbidden', message: 'USERS_READ missing', traceId: null }),
    );
    const client = new ApiClient({ baseUrl: 'http://x', onAuthLost, refresh: refreshMock });
    await expect(client.get('/v1/admin/users')).rejects.toMatchObject({ code: 'forbidden' });
    expect(refreshMock).not.toHaveBeenCalled();
    expect(onAuthLost).not.toHaveBeenCalled();
  });

  it('concurrent 401s share a single in-flight refresh', async () => {
    tokenStore.setRefresh('rt');
    refreshMock.mockImplementation(async () => {
      await new Promise((r) => setTimeout(r, 20));
      tokenStore.setAccess('tok-fresh');
      return true;
    });
    const f = globalThis.fetch as ReturnType<typeof vi.fn>;
    // First call per path 401s; the post-refresh retry succeeds.
    const calls: Record<string, number> = {};
    f.mockImplementation((url: string) => {
      calls[url] = (calls[url] ?? 0) + 1;
      if (calls[url] === 1) {
        return Promise.resolve(jsonRes(401, { code: 'unauthorized', message: 'x', traceId: null }));
      }
      return Promise.resolve(jsonRes(200, { path: url }));
    });
    const client = new ApiClient({ baseUrl: 'http://x', onAuthLost, refresh: refreshMock });
    const [a, b] = await Promise.all([client.get<{ path: string }>('/a'), client.get<{ path: string }>('/b')]);
    expect(a.path).toBe('http://x/a');
    expect(b.path).toBe('http://x/b');
    // The refresh fn itself was entered exactly once despite two 401s.
    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(onAuthLost).not.toHaveBeenCalled();
  });
});
