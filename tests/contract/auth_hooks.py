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
5. DELETE /v1/me/sessions/{familyId} PATH REWRITE (positive-mode cases only) -
   the op's only parameter is a plain `type: string` familyId, so generated
   values are junk and every authenticated case answered 404 forever (zero
   positive coverage; the auth-health warning fired because the op's mutated
   auth cases are all 401/403). Positive cases get a REAL rider-owned familyId
   (fresh lookup after each 204), i.e. the documented 204 revoke path is
   actually exercised; negative-mode cases keep their junk/missing params.

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
_own_session_cache = {"family_id": None}


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


def _fetch_own_session_family_id(token: str) -> str | None:
    """Newest familyId of the rider's live sessions, or None on any failure."""
    try:
        r = requests.get(
            f"{BACKEND_URL}/v1/me/sessions",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        sessions = r.json().get("sessions", []) if r.status_code == 200 else []
    except Exception:
        return None
    if not sessions:
        return None
    return sessions[-1]["familyId"]


def _own_session_family_id() -> str | None:
    """A familyId from the rider's OWN live sessions (for the DELETE rewrite).

    Revoking a family never invalidates ACCESS tokens (currentUser verifies the
    JWT signature + user status only), so targeting any listed family is safe
    for the run. The newest entry is preferred: it is the least likely to be
    the family behind the cached access token.

    NEVER re-authenticates: by the time DELETE cases run, the login/password
    override cases have usually tripped the rider's brute-force lockout
    (P1-3), so a fresh _session_for would 429 -> None forever. Instead the
    family lookup piggybacks on whatever token the auth layer already primed
    (GET /v1/me/sessions cases run before the DELETE ones and prime it), and
    ContractAuth.get primes the cache at the first fresh login.
    """
    with _lock:
        fid = _own_session_cache["family_id"]
        if fid:
            return fid
        token = _access["rider"]
    if not token:
        return None
    fid = _fetch_own_session_family_id(token)
    if fid:
        with _lock:
            _own_session_cache["family_id"] = fid
    return fid


_RANDOM_UUID = "00000000-0000-0000-0000-000000000000"

# P4-2: rider-owned ride priming for the /v1/rides/{id} rewrites (same pattern as
# the session familyId above). GET never transitions; CANCEL does - so cancel
# cases always get a FRESH ride (a cached id would be terminal after the first
# cancel -> 409 -> positive failure), while GET reuses the cached one.
_own_ride_cache = {"ride_id": None}


def _create_ride(token: str) -> str | None:
    """Book a CASH/monaco/standard ride for the rider; returns the id or None."""
    try:
        r = requests.post(
            f"{BACKEND_URL}/v1/rides",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json={
                "pickupLat": 43.7384, "pickupLon": 7.4246,
                "dropoffLat": 43.7543, "dropoffLon": 7.4312,
                "city": "monaco", "rideType": "standard", "paymentMethod": "CASH",
            },
            timeout=10,
        )
        if r.status_code == 201:
            return r.json().get("id")
    except Exception:
        pass
    return None


def _own_ride_id_for_get() -> str | None:
    """Cached rider-owned ride id for GET /v1/rides/{id} (GET only expires, never cancels)."""
    with _lock:
        cached = _own_ride_cache["ride_id"]
        token = _access["rider"]
    if cached:
        return cached
    if not token:
        return None
    rid = _create_ride(token)
    if rid:
        with _lock:
            _own_ride_cache["ride_id"] = rid
    return rid


def _fresh_ride_id_for_cancel() -> str | None:
    """A FRESH REQUESTED ride id per positive cancel case (a terminal ride would 409)."""
    with _lock:
        token = _access["rider"]
    if not token:
        return None
    return _create_ride(token)


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
            if kind == "rider" and token:
                # Prime the DELETE-rewrite cache at the FIRST fresh login
                # (later lookups must not re-login: the login/password
                # override cases trip the brute-force lockout and any fresh
                # _session_for would just 429).
                fid = _fetch_own_session_family_id(token)
                if fid:
                    with _lock:
                        _own_session_cache["family_id"] = fid
        return token

    def set(self, case, data, ctx):
        case.headers = case.headers or {}
        case.headers["Authorization"] = f"Bearer {data}"


@schemathesis.hook
def before_call(ctx, case, **kwargs):
    path = case.operation.path
    body = case.body if isinstance(case.body, dict) else None
    # DELETE /v1/me/sessions/{familyId} is BODYLESS - rewrite before the
    # body-None early return (docstring 5).
    if path == "/v1/me/sessions/{familyId}":
        params = case.path_parameters or {}
        mode = getattr(getattr(case, "meta", None), "generation", None)
        mode = getattr(mode, "mode", None)
        if "familyId" in params and getattr(mode, "name", "") != "NEGATIVE":
            fid = _own_session_family_id()
            if fid:
                case.path_parameters = {**params, "familyId": fid}
                # NOTE: deliberately NOT forcing the Authorization header here.
                # A forced real header turns negative-mode auth-mutation cases
                # into accidental 204s (transport may override the mutated
                # auth) -> ST401. The rewrite alone yields 204s whenever the
                # transport applies the cached token; the residual auth-health
                # warning on this op is a probe artifact (deep-diagnosed in
                # round 6), not a server defect.
    # P4-2 path-parameter rewrites (rider-owned ride priming; see the helpers above).
    # These are BODYLESS operations (GET /v1/rides/{id}, POST /v1/rides/{id}/cancel) -
    # they MUST run before the body-None early return below (familyId lesson).
    # GET reuses a cached ride (GET only expires, never transitions to terminal);
    # CANCEL gets a FRESH ride per positive case (cancel is NOT repeatable-safe,
    # unlike the familyId revoke above).
    ride_params = case.path_parameters or {}
    ride_mode = getattr(getattr(getattr(case, "meta", None), "generation", None), "mode", None)
    ride_negative = getattr(ride_mode, "name", "") == "NEGATIVE"
    if path == "/v1/rides/{id}" and "id" in ride_params and not ride_negative:
        rid = _own_ride_id_for_get()
        if rid:
            case.path_parameters = {**ride_params, "id": rid}
    elif path == "/v1/rides/{id}/cancel" and "id" in ride_params and not ride_negative:
        rid = _fresh_ride_id_for_cancel()
        if rid:
            case.path_parameters = {**ride_params, "id": rid}
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
    elif path == "/v1/admin/config/login-methods":
        # The designed 400 empty_patch (D70: an all-absent patch must not burn a
        # version) is schema-invisible - {"otpAutoRegister": null} passes
        # minProperties because null == absent (D46) - and the coverage phase
        # probes it forever. The refusal is pinned by ConfigServiceTest, so the
        # contract run exercises the positive path: rewrite to an all-null
        # channel entry, which the server treats as a no-op bump (200).
        present = [
            k
            for k, v in body.items()
            # Only KNOWN patch fields with non-null values make the patch
            # non-absent (D46: null == absent). Unknown keys ("", garbage) are
            # server-ignored (ignoreUnknownKeys) - counting them as present
            # skipped the rewrite and let an all-absent patch 400 empty_patch
            # on a schema-valid fuzzing body (round-6 ST202).
            if k in ("passwordLoginEnabled", "otpAutoRegister") and v is not None
        ]
        channels = body.get("otpChannels")
        if not present and (channels is None or channels == {}):
            case.body = {"otpChannels": {"SMS": {"enabled": None, "priority": None}}}
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
    elif path == "/v1/rides/estimate" and "pickupLat" in body and "city" in body:
        # P4-1 (round-11 hooks): the city/rideType keys are DATA (configured in the
        # live bundle), not static schema enums - a pattern-valid but unconfigured
        # key ("abc") is a designed 400 unknown_city/unknown_ride_type, which the
        # D49 positive-acceptance phase cannot know about. Positive/coverage modes
        # estimate the BOOTSTRAP city (monaco) with all its ride types (rideType
        # null); negative mode keeps schema-derived values so the 400s stay covered.
        mode = getattr(getattr(getattr(case, "meta", None), "generation", None), "mode", None)
        if getattr(mode, "name", "") != "NEGATIVE":
            case.body = {**body, "city": "monaco", "rideType": None}
    elif path == "/v1/admin/config/fares" and "cities" in body:
        # P4-1 (D75 full-replace): positive-mode documents must satisfy the whole
        # nested validation (propertyNames patterns, ISO currency, hour windows).
        # Nested propertyNames-pattern generation is not reliable across schemathesis
        # versions - pin the bootstrap-valid document for positive/coverage modes;
        # negative mode keeps the mutated bodies (server 400s are designed).
        mode = getattr(getattr(getattr(case, "meta", None), "generation", None), "mode", None)
        if getattr(mode, "name", "") != "NEGATIVE":
            case.body = {
                "straightLine": {"detourFactor": 1.3, "assumedAvgSpeedKmh": 22.0},
                "quote": {"validitySeconds": 90, "mode": "upfront_locked", "meteredCapMultiplier": 1.5},
                "cities": {
                    "monaco": {
                        "currency": "EUR",
                        "rideTypes": {
                            "standard": {
                                "base": 2.5, "perKm": 1.8, "perMinute": 0.45,
                                "minimumFare": 8.0, "bookingFee": 1.5,
                                "cancelFee": 3.0, "waitFeePerMinute": 0.5,
                                "night": {"multiplier": 1.25, "startHourUtc": 22, "endHourUtc": 6},
                                "peak": {"multiplier": 1.0, "startHourUtc": 0, "endHourUtc": 0},
                            }
                        },
                    }
                },
            }
    elif path == "/v1/rides" and "pickupLat" in body:
        # P4-2 (D49 positive-acceptance): city/rideType are DATA (configured), not
        # static enums - pattern-valid unconfigured keys are designed 400s. Positive/
        # coverage modes book the bootstrap city/rideType with CASH (the only Phase-4
        # payment method; the schema pins the enum anyway). Negative mode keeps the
        # generated body so the designed 400s stay covered.
        mode = getattr(getattr(getattr(case, "meta", None), "generation", None), "mode", None)
        if getattr(mode, "name", "") != "NEGATIVE":
            case.body = {**body, "city": "monaco", "rideType": "standard", "paymentMethod": "CASH"}



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
    elif path == "/v1/me/sessions/{familyId}" and response.status_code == 204:
        # The cached family is now revoked - look up a fresh one for the next
        # positive case so the 204 path keeps being exercised.
        with _lock:
            _own_session_cache["family_id"] = None
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
