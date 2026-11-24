"""P1-CONTRACT: authenticated schemathesis coverage (register/login hooks).

Loaded via `SCHEMATHESIS_HOOKS=tests/contract/auth_hooks.py` in the api-contract CI
job (infra/docker-compose.yml backend runs TEST_PROFILE=true there) and for local
native-backend runs. What it provides over the plain (401-only) run:

1. GLOBAL BEARER AUTH - a RIDER session (register 409-tolerant, then login/password)
   for every operation, and the compose BOOTSTRAP ADMIN session for /v1/admin/*
   (AUTH_BOOTSTRAP_ADMIN_* env pair, first ADMIN_SUPER at backend startup). Admin
   endpoints get positive 200 coverage instead of 401-only warnings.
2. login/password FULL-SHAPE OVERRIDE - generated cases that already carry both
   `identifier` and `password` are upgraded to the live rider credentials -> a
   guaranteed positive 200 (each login opens a session; lockout/brute-force paths
   stay covered by the backend unit suite). Partial bodies (coverage-phase
   missing-field probes) keep their negative path.
3. otp/verify FULL-SHAPE OVERRIDE - requests one SMS OTP for the rider phone
   (dev-inbox adapter; SMS is the default-enabled devOnly channel), fetches the
   code via /test/otp/latest (TEST_PROFILE=true only) and replays the REAL
   requestId+code: the first full-shape case is a positive 200, later ones
   exercise the consumed-code path (documented 401/400s).
4. refresh FULL-SHAPE OVERRIDE with the LIVE refresh token; after_call keeps the
   rotation chain current on 200 and self-heals on 401 (the family-reuse
   revocation is documented behavior - under parallel workers a stale refresh can
   revoke the family; the next case re-logs in transparently).

Intentionally NOT overridden: logout bodies (a positive logout revokes the whole
rider family mid-run and would 401 every later rider case; positive logout is
covered by backend unit tests + the compose Playwright project, P0-C/D68).
"""

import os
import threading

import requests
import schemathesis

BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080").rstrip("/")

RIDER_PHONE = "15550077777"
RIDER_EMAIL = "st-rider@example.com"
RIDER_PASSWORD = "contract-horse-battery-42"
RIDER_DISPLAY_NAME = "ST Contract Rider"

ADMIN_PHONE = "15550088888"
ADMIN_PASSWORD = "contract-admin-pass-42"

_lock = threading.Lock()
_access = {"rider": None, "admin": None}
_refresh_token = {"rider": None}
_otp_cache = {"request_id": None, "code": None}
_admin_id_cache = {"id": None, "failed": False}


def _tokens_of(payload: dict) -> dict | None:
    tokens = payload.get("tokens") if isinstance(payload, dict) else None
    if isinstance(tokens, dict) and tokens.get("accessToken") and tokens.get("refreshToken"):
        return tokens
    return None


def _register_rider() -> requests.Response:
    return requests.post(
        f"{BACKEND_URL}/v1/auth/register",
        json={
            "displayName": RIDER_DISPLAY_NAME,
            "password": RIDER_PASSWORD,
            "phone": RIDER_PHONE,
            "email": RIDER_EMAIL,
        },
        timeout=10,
    )


def _login(identifier: str, password: str) -> requests.Response:
    return requests.post(
        f"{BACKEND_URL}/v1/auth/login/password",
        json={"identifier": identifier, "password": password},
        timeout=10,
    )


def _session_for(kind: str) -> str | None:
    """Fresh bearer token for `kind` (rider via register->login, admin via login)."""
    if kind == "rider":
        r = _register_rider()
        if r.status_code == 201:
            tokens = _tokens_of(r.json())
            if tokens:
                with _lock:
                    _refresh_token["rider"] = tokens["refreshToken"]
                return tokens["accessToken"]
        # 409 (already registered from a previous run) or anything unexpected -> login.
        r = _login(RIDER_PHONE, RIDER_PASSWORD)
        if r.status_code == 200:
            tokens = _tokens_of(r.json())
            if tokens:
                with _lock:
                    _refresh_token["rider"] = tokens["refreshToken"]
                return tokens["accessToken"]
        return None
    # Admin: bootstrapped by the compose env pair. None (unauthenticated cases,
    # documented 401/403) when running against a backend without the bootstrap.
    r = _login(ADMIN_PHONE, ADMIN_PASSWORD)
    if r.status_code == 200:
        tokens = _tokens_of(r.json())
        return tokens["accessToken"] if tokens else None
    return None


def _request_otp() -> tuple[str, str] | None:
    """One SMS OTP request + dev-inbox fetch; cached (send cap 3/10min)."""
    with _lock:
        if _otp_cache["request_id"] and _otp_cache["code"]:
            return _otp_cache["request_id"], _otp_cache["code"]
        r = requests.post(
            f"{BACKEND_URL}/v1/auth/otp/request",
            json={"channel": "sms", "target": RIDER_PHONE},
            timeout=10,
        )
        if r.status_code != 200:
            return None
        request_id = r.json().get("requestId")
        code_r = requests.get(
            f"{BACKEND_URL}/test/otp/latest", params={"target": RIDER_PHONE}, timeout=10
        )
        code = code_r.json().get("code") if code_r.status_code == 200 else None
        if not request_id or not code:
            return None
        _otp_cache["request_id"] = request_id
        _otp_cache["code"] = code
        return request_id, code


def _admin_self_id() -> str | None:
    """The bootstrap admin's user id (lazily resolved via the admin list).
    Used to detect SELF-targeted mutations whose 400 refusal is DESIGNED behavior
    (P1-3: no self force-logout, no self role/status change) - those rules are
    pinned by the backend unit suite, so the contract run exercises the positive
    path on a random UUID (documented 404) instead of tripping ST202 forever."""
    with _lock:
        if _admin_id_cache["id"]:
            return _admin_id_cache["id"]
        if _admin_id_cache["failed"]:
            return None
    token = _session_for("admin")
    if not token:
        return None
    r = requests.get(
        f"{BACKEND_URL}/v1/admin/users",
        params={"query": ADMIN_PHONE, "limit": 5},
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    if r.status_code == 200:
        for u in r.json().get("users", []):
            if u.get("phone") == ADMIN_PHONE:
                with _lock:
                    _admin_id_cache["id"] = u["id"]
                return u["id"]
    with _lock:
        _admin_id_cache["failed"] = True
    return None


_RANDOM_UUID = "00000000-0000-0000-0000-000000000000"


@schemathesis.auth(cache_by_key=lambda case, ctx: "admin" if case.operation.path.startswith("/v1/admin") else "rider")
class ContractAuth:
    """Rider bearer for the API; the bootstrap admin only for /v1/admin/*.

    cache_by_key keeps TWO cache entries (rider / admin) - without it the caching
    wrapper stores one token for the whole run and admin ops get the rider 403.
    """

    def get(self, case, ctx):
        op = getattr(ctx, "operation", None) or case.operation
        kind = "admin" if op.path.startswith("/v1/admin") else "rider"
        with _lock:
            token = _access[kind]
        if token is None:
            token = _session_for(kind)
            with _lock:
                _access[kind] = token
        return token

    def set(self, case, data, ctx):
        case.headers = case.headers or {}
        case.headers["Authorization"] = f"Bearer {data}"


@schemathesis.hook
def before_call(ctx, case, **kwargs):
    path = case.operation.path
    body = case.body if isinstance(case.body, dict) else None
    if body is None:
        return
    # Self-targeted mutation rewrites (designed 400 refusals are unit-tested; see _admin_self_id).
    admin_self = None
    if path.startswith("/v1/admin/users/{id}"):
        admin_self = _admin_self_id()
        if admin_self and case.path_parameters.get("id") == admin_self:
            if path == "/v1/admin/users/{id}/force-logout" or ("role" in body or "status" in body):
                case.path_parameters = {**case.path_parameters, "id": _RANDOM_UUID}
                return
    # Full-shape-only upgrades (coverage-phase partial bodies keep their negative path).
    if path == "/v1/auth/login/password" and "identifier" in body and "password" in body:
        case.body = {**body, "identifier": RIDER_PHONE, "password": RIDER_PASSWORD}
    elif path == "/v1/auth/refresh" and "refreshToken" in body:
        with _lock:
            token = _refresh_token["rider"]
        if token:
            case.body = {**body, "refreshToken": token}
    elif path == "/v1/auth/otp/verify" and "requestId" in body and "code" in body:
        pair = _request_otp()
        if pair:
            request_id, code = pair
            case.body = {**body, "requestId": request_id, "code": code}


@schemathesis.hook
def after_call(ctx, case, response):
    path = case.operation.path
    if path == "/v1/auth/refresh" and response.status_code == 200:
        try:
            data = response.json()
        except Exception:
            return
        tokens = _tokens_of(data)
        if tokens:
            with _lock:
                _access["rider"] = tokens["accessToken"]
                _refresh_token["rider"] = tokens["refreshToken"]
    elif response.status_code == 401:
        # Self-heal: a 401 on a request WE authenticated (family revoked by a
        # refresh race / consumed code) drops the stored pair; the next get()
        # re-logs in. Unauthenticated documented 401s are untouched.
        headers = case.headers or {}
        if headers.get("Authorization"):
            op = case.operation
            kind = "admin" if op.path.startswith("/v1/admin") else "rider"
            with _lock:
                _access[kind] = None
                if kind == "rider":
                    _refresh_token["rider"] = None
