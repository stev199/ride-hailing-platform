package com.ridehailing.backend

import com.ridehailing.backend.auth.Identifiers
import com.ridehailing.backend.auth.PasswordService
import com.ridehailing.backend.config.CancellationConfig
import com.ridehailing.backend.config.PromoCodeConfig
import com.ridehailing.backend.config.PromoConfig
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D49 contract alignment. The OpenAPI request schemas must be generator-precise:
 * every schema-valid value must be accepted by the backend (Schemathesis
 * positive_data_acceptance / ST202) and every schema-invalid value must be
 * rejected with 4xx (negative_data_rejection). These tests prove the documented
 * constraints in docs/api/openapi.yaml are EXACTLY the enforced validation
 * rules, so the documented contract and the code cannot drift apart.
 */
class ContractAlignmentTest {

    @Suppress("UNCHECKED_CAST")
    private val rootSpec: Map<String, Any> by lazy {
        val candidates = listOf(File("../docs/api/openapi.yaml"), File("docs/api/openapi.yaml"))
        val file = candidates.firstOrNull { it.isFile }
            ?: fail("openapi.yaml not found relative to ${File("").absolutePath}")
        Yaml().load(file.inputStream()) as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Any> by lazy {
        val components = rootSpec["components"] as Map<String, Any>
        components["schemas"] as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun getQueryParam(path: String, name: String): Map<String, Any> {
        val op = ((rootSpec["paths"] as? Map<String, Any>)?.get(path) as? Map<String, Any>)
            ?.get("get") as? Map<String, Any> ?: fail("$path has no GET operation")
        val params = op["parameters"] as? List<Map<String, Any>> ?: fail("$path GET has no parameters")
        return params.firstOrNull { it["name"] == name }?.get("schema") as? Map<String, Any>
            ?: fail("$path GET parameter $name missing")
    }

    @Suppress("UNCHECKED_CAST")
    private fun prop(schema: String, name: String): Map<String, Any> {
        val props = schemas[schema]?.let { it as Map<String, Any> }?.get("properties") as? Map<String, Any>
            ?: fail("schema $schema has no properties")
        return props[name] as? Map<String, Any> ?: fail("$schema.$name missing in openapi.yaml")
    }

    private fun pattern(schema: String, name: String): Regex {
        val raw = prop(schema, name)["pattern"] as? String
            ?: fail("$schema.$name has no pattern - contract drift (D49)")
        return Regex(raw)
    }

    private fun int(schema: String, name: String, key: String): Int =
        (prop(schema, name)[key] as? Int) ?: fail("$schema.$name.$key missing")

    /** Backend ground truth for displayName (AuthService.register, D49 ASCII-trim). */
    private fun displayNameAccepted(raw: String): Boolean =
        raw.length in 2..64 &&
            raw.trim(' ', '\t', '\n', '\u000B', '\u000C', '\r').isNotEmpty()

    private val passwords = PasswordService()

    @Test
    fun `phone pattern mirrors digit-count normalization`() {
        val re = pattern("RegisterRequest", "phone")
        val separators = listOf("", "-", " ", " - ", "+", "()", ".")
        for (digits in 0..21) {
            val core = "9".repeat(digits)
            for (sep in separators) {
                val raw = if (core.isEmpty()) sep.padEnd(3, 'x') else core.chunked(3).joinToString(sep)
                val expected = digits in 8..15
                assertEquals(expected, re.matches(raw), "pattern mismatch for '$raw' ($digits digits)")
                assertEquals(expected, Identifiers.normalizePhone(raw) != null, "backend mismatch for '$raw'")
            }
        }
        // Unicode digits are not ASCII phone digits in either the schema or the backend.
        val arabicDigits = "٩٩٩٩٩٩٩٩"
        assertTrue(!re.matches(arabicDigits) && Identifiers.normalizePhone(arabicDigits) == null)
    }

    @Test
    fun `email pattern mirrors identifier validation`() {
        val re = pattern("RegisterRequest", "email")
        val good = listOf(
            "a@b", "user.name@host.io", "0@0.com", "a@b.c.d", "a b@x", "a@b. ",
            "x@y+z", "A@B.CO", ".a@b", "a@b..c",
        )
        val bad = listOf("", "@", "a@", "@b", "a@b.", "ab", "no-at-sign", "a@", "a@b@c", "a@b@c@")
        for (raw in good) {
            assertTrue(re.matches(raw), "schema must accept '$raw'")
            assertTrue(Identifiers.normalizeEmail(raw) != null, "backend must accept '$raw'")
        }
        for (raw in bad) {
            assertTrue(!re.matches(raw), "schema must reject '$raw'")
            assertEquals(null, Identifiers.normalizeEmail(raw), "backend must reject '$raw'")
        }
    }

    @Test
    fun `displayName constraints mirror validation`() {
        assertEquals(2, int("RegisterRequest", "displayName", "minLength"))
        assertEquals(64, int("RegisterRequest", "displayName", "maxLength"))
        val re = pattern("RegisterRequest", "displayName")
        val cases = mapOf(
            "ab" to true, "a b" to true, " a " to true, "  x  " to true, "  \t x " to true,
            "\u2028\u2028" to true,
            "a" to false, "" to false, " " to false, "   " to false, "\t\n" to false,
            "x".repeat(64) to true, "x".repeat(65) to false,
        )
        for ((raw, expected) in cases) {
            // Schema validity = minLength AND maxLength AND pattern. The pattern is an
            // UNANCHORED search ('[^ \t\n\v\f\r ]' = contains at least one non-ASCII-
            // whitespace char) - engine-proof across kotlin/python/ECMA regex semantics.
            val schemaValid = raw.length in 2..64 && re.containsMatchIn(raw)
            assertEquals(expected, schemaValid, "schema mismatch for '$raw'")
            assertEquals(expected, displayNameAccepted(raw), "backend mismatch for '$raw'")
        }
    }

    @Test
    fun `password bounds mirror policy`() {
        assertEquals(10, int("RegisterRequest", "password", "minLength"))
        assertEquals(128, int("RegisterRequest", "password", "maxLength"))
        for (len in listOf(0, 9, 10, 64, 128, 129, 200)) {
            val pw = "x".repeat(len)
            val documentedOk = len in 10..128
            assertEquals(documentedOk, passwords.policyViolation(pw) == null, "policy mismatch for length $len")
        }
    }

    @Test
    fun `locale is documented as a server-defaulted free string`() {
        for (s in listOf("RegisterRequest", "OtpRequest", "OtpVerify")) {
            val loc = prop(s, "locale")
            assertTrue(loc["type"].toString().contains("string"), "$s.locale type")
            assertTrue(!loc.containsKey("enum") && !loc.containsKey("pattern"), "$s.locale must not constrain values")
        }
    }

    @Test
    fun `otp displayName is unconstrained because the server falls back to Rider`() {
        val d = prop("OtpVerify", "displayName")
        assertTrue(!d.containsKey("minLength") && !d.containsKey("maxLength") && !d.containsKey("pattern"))
    }

    @Test
    fun `otp code is documented as 6 numeric digits`() {
        assertEquals(6, int("OtpVerify", "code", "minLength"))
        assertEquals(6, int("OtpVerify", "code", "maxLength"))
        val re = pattern("OtpVerify", "code")
        assertTrue(re.matches("123456"))
        assertTrue(!re.matches("12345"))
        assertTrue(!re.matches("12345a"))
    }

    @Test
    fun `optional request fields document null exactly as the DTOs treat it`() {
        // Nullable DTO fields accept JSON null (treated as absent) -> 2xx, so the
        // schema must also mark them nullable (ST201 negative_data_rejection).
        for ((s, p) in listOf(
            "RegisterRequest" to "phone", "RegisterRequest" to "email", "RegisterRequest" to "locale",
            "OtpRequest" to "locale", "OtpVerify" to "displayName", "OtpVerify" to "locale",
        )) {
            val type = prop(s, p)["type"].toString().replace(" ", "")
            assertTrue(type.contains("null") && type.contains("string"), "$s.$p must allow null")
        }
        // Non-nullable DTO fields reject JSON null with 400 -> schema stays type: string.
        for ((s, p) in listOf(
            "RegisterRequest" to "displayName", "RegisterRequest" to "password",
            "PasswordLoginRequest" to "identifier", "PasswordLoginRequest" to "password",
            "OtpRequest" to "channel", "OtpRequest" to "target",
            "OtpVerify" to "requestId", "OtpVerify" to "code",
            "RefreshRequest" to "refreshToken", "LogoutRequest" to "refreshToken",
        )) {
            assertEquals("string", prop(s, p)["type"], "$s.$p must not allow null (DTO is non-null)")
        }
        // anyOf branches must guard identifier non-nullness: {phone: null, email: null}
        // must be schema-INVALID (backend answers 400 identifier_required).
        @Suppress("UNCHECKED_CAST")
        val branches = (schemas["RegisterRequest"] as Map<String, Any>)["anyOf"] as List<Map<String, Any>>
        assertTrue(branches.size == 2, "RegisterRequest.anyOf must have exactly two branches")
        for (b in branches) {
            assertTrue(b.containsKey("required") && b.containsKey("properties"), "anyOf branches must type-guard their identifier (D49)")
        }
    }

    @Test
    fun `stored user phone shape is documented`() {
        val schema = prop("User", "phone")
        assertEquals(listOf("string", "null"), schema["type"])
        val re = pattern("User", "phone")
        assertTrue(re.matches("33123456789"))
        assertTrue(!re.matches("331234567890123456789"))
        assertTrue(!re.matches("331234 56789"))
    }

    @Test
    fun `admin create phone mirrors the enforced normalization - P1-CONTRACT ST202 fix`() {
        val register = pattern("RegisterRequest", "phone")
        val admin = pattern("AdminCreateUser", "phone")
        assertEquals(register.pattern, admin.pattern, "admin create enforces the same phone shape (AdminService.invalid_phone)")
        for (raw in listOf("33620000003", "00000000", "12345678", "1234567", "1234567890123456", "+33612345678", "336-12345678", "331234 56789")) {
            val expected = Identifiers.normalizePhone(raw) != null
            assertEquals(expected, admin.matches(raw), "AdminCreateUser.phone mismatch for '$raw'")
            assertEquals(expected, register.matches(raw), "register/admin pattern drift for '$raw'")
        }
    }

    @Test
    fun `admin patch locale is an enum of the server-supported set - P1-CONTRACT ST202 fix`() {
        val loc = prop("AdminPatchUser", "locale")
        val values = loc["enum"] as? List<*> ?: fail("AdminPatchUser.locale must be an enum (AdminService 400 invalid_locale)")
        assertEquals(setOf("en", "fr", "ar", null), values.toSet(), "patch supports en/fr/ar plus absent-null")
    }

    @Test
    fun `admin create email mirrors the enforced normalization - P1-CONTRACT round 2 ST202 fix`() {
        val register = pattern("RegisterRequest", "email")
        val admin = pattern("AdminCreateUser", "email")
        assertEquals(register.pattern, admin.pattern, "admin create enforces the same email shape (AdminService invalid_email)")
        for (raw in listOf("a@b", "user.name@host.io", "0@0.com", "", "@", "a@", "@b", "a@b.", "a@b@c")) {
            val expected = Identifiers.normalizeEmail(raw) != null
            assertEquals(expected, admin.matches(raw), "AdminCreateUser.email mismatch for '$raw'")
        }
    }

    @Test
    fun `channel patch priority is Int32-bounded and non-negative - P1-CONTRACT round 2 ST202 fix`() {
        val pr = prop("ChannelPatch", "priority")
        assertEquals(0, (pr["minimum"] as? Int) ?: (pr["minimum"] as? Long)?.toInt() ?: fail("ChannelPatch.priority.minimum missing"), "negative priority is 400 invalid_priority (D70)")
        assertEquals(2147483647, (pr["maximum"] as? Int) ?: (pr["maximum"] as? Long)?.toInt() ?: fail("ChannelPatch.priority.maximum missing"), "server parses Int32 - larger values break the body parser (invalid_body)")
    }

    @Test
    fun `login methods patch is schema-precise on empty bodies and channel keys - P1-CONTRACT ST202 fix`() {
        @Suppress("UNCHECKED_CAST")
        val schema = schemas["LoginMethodsPatch"] as? Map<String, Any> ?: fail("LoginMethodsPatch missing")
        assertEquals(1, schema["minProperties"], "an all-absent body must be schema-invalid: the server answers 400 empty_patch (D70)")
        @Suppress("UNCHECKED_CAST")
        val otp = (schema["properties"] as Map<String, Any>)["otpChannels"] as? Map<String, Any> ?: fail("otpChannels missing")
        @Suppress("UNCHECKED_CAST")
        val names = otp["propertyNames"] as? Map<String, Any> ?: fail("otpChannels must constrain keys via propertyNames (unknown_channel is 400)")
        @Suppress("UNCHECKED_CAST")
        val keys = names["enum"] as List<*>
        assertEquals(setOf("SMS", "TELEGRAM", "EMAIL", "WHATSAPP"), keys.toSet())
    }

    // ---------- P4-1 fare engine contract alignment ----------

    private fun numberOf(p: Map<String, Any>, field: String, schema: String): Double {
        val v = p[field] ?: fail("$schema.$field missing")
        return when (v) {
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is Double -> v
            else -> fail("$schema.$field must be a number, got $v")
        }
    }

    private fun numberBounds(schema: String, field: String): Pair<Double, Double> {
        val p = prop(schema, field)
        return numberOf(p, "minimum", schema) to numberOf(p, "maximum", schema)
    }

    @Test
    fun `fare estimate request mirrors the server coordinate and key validation`() {
        for (f in listOf("pickupLat", "pickupLon", "dropoffLat", "dropoffLon")) {
            val (min, max) = numberBounds("FareEstimateRequest", f)
            assertEquals(-180.0, min, "$f lower bound (server accepts +/-180 on both axes)")
            assertEquals(180.0, max, "$f upper bound")
        }
        val city = pattern("FareEstimateRequest", "city").pattern
        assertEquals(city, pattern("FareEstimateRequest", "rideType").pattern, "city and rideType share the kebab key shape")
        assertEquals(Regex(city).matches("monaco"), true)
        assertEquals(false, Regex(city).matches("Nice"), "uppercase keys are rejected (FareConfig CITY_KEY_RE)")
    }

    @Test
    fun `fare config schema mirrors FareConfig validation limits`() {
        // Map cardinality + key shapes (server: MAX_CITIES / MAX_RIDE_TYPES_PER_CITY / CITY_KEY_RE).
        @Suppress("UNCHECKED_CAST")
        val cities = (schemas["FareConfig"] as? Map<String, Any>)?.get("properties")?.let { it as Map<String, Any> }?.get("cities") as? Map<String, Any>
            ?: fail("FareConfig.cities missing")
        assertEquals(1, cities["minProperties"], "empty fares section is 400 invalid_fares (at least one city)")
        assertEquals(16, cities["maxProperties"], "device-cached bundle size guard (MAX_CITIES)")
        @Suppress("UNCHECKED_CAST")
        val names = cities["propertyNames"] as? Map<String, Any> ?: fail("city keys constrained via propertyNames")
        val cityRe = (names["pattern"] as? String) ?: fail("city key pattern missing")
        assertEquals(Regex(cityRe).matches("abc-12"), true)
        assertEquals(Regex(cityRe).matches("UPPER"), false)

        // Surge windows (server: multiplier in 1.0..5.0, hours 0..23, start==end disables).
        val (multMin, multMax) = numberBounds("SurgeWindow", "multiplier")
        assertEquals(1.0, multMin)
        assertEquals(5.0, multMax)
        for (h in listOf("startHourUtc", "endHourUtc")) {
            val (hmin, hmax) = numberBounds("SurgeWindow", h)
            assertEquals(0.0, hmin, "$h lower bound")
            assertEquals(23.0, hmax, "$h upper bound")
        }

        // Straight-line calibration (server: detour in 1.0..3.0, speed in (0..200]).
        val (detMin, detMax) = numberBounds("StraightLineCalibration", "detourFactor")
        assertEquals(1.0, detMin); assertEquals(3.0, detMax)
        @Suppress("UNCHECKED_CAST")
        val speed = prop("StraightLineCalibration", "assumedAvgSpeedKmh")
        // OAS 3.1 numeric exclusiveMinimum: strictly x > 0 (0 is invalid - division guard).
        val speedExcl = (speed["exclusiveMinimum"] as? Number)?.toDouble()
            ?: fail("speed exclusiveMinimum (numeric, OAS 3.1) missing")
        assertEquals(0.0, speedExcl, "speed 0 is invalid (division guard)")
        assertEquals(200.0, (speed["maximum"] as? Int)?.toDouble() ?: (speed["maximum"] as? Double) ?: fail("speed maximum missing"))
    }

    @Test
    fun `ride booking request mirrors the server validation (P4-2)`() {
        // Coordinates: same +/-180 bounds as the estimate endpoint (server: invalid_coordinates).
        for (f in listOf("pickupLat", "pickupLon", "dropoffLat", "dropoffLon")) {
            val (min, max) = numberBounds("RideCreateRequest", f)
            assertEquals(-180.0, min, "$f lower bound")
            assertEquals(180.0, max, "$f upper bound")
        }
        assertEquals(
            pattern("FareEstimateRequest", "city").pattern,
            pattern("RideCreateRequest", "city").pattern,
            "booking and estimate share the city key shape",
        )
        // Payment method enum mirrors the server acceptance set (P7-2: CASH + WALLET;
        // unknown methods stay payment_method_unavailable, metered+WALLET is 400).
        @Suppress("UNCHECKED_CAST")
        val pm = prop("RideCreateRequest", "paymentMethod")["enum"] as List<*>
        assertEquals(listOf("CASH", "WALLET"), pm)
        // Note length limit (server: note_too_long at >500).
        val note = prop("RideCreateRequest", "note")
        assertEquals(500, (note["maxLength"] as? Int) ?: (note["maxLength"] as? Long)?.toInt() ?: fail("note.maxLength missing"))
    }

    @Test
    fun `ride list pagination mirrors the server bounds (P4-2)`() {
        val (lmin, lmax) = numberBounds("RideListDto", "limit")
        assertEquals(1.0, lmin, "server: limit in 1..100 (invalid_pagination)")
        assertEquals(100.0, lmax)
        val omin = prop("RideListDto", "offset")["minimum"] as? Int ?: fail("offset.minimum missing")
        assertEquals(0, omin, "server: offset >= 0")
        // Round-12 ST202: the server parses offset with toIntOrNull() (Int32). The query
        // param AND the response echo must cap at Int32.MAX_VALUE, otherwise the generator
        // produces 9223372036854775808 - schema-valid but rejected 400 (violates the
        // D49 generator-precision rule).
        val echoMax = prop("RideListDto", "offset")["maximum"] as? Int ?: fail("offset.maximum missing")
        assertEquals(2147483647, echoMax, "server: offset parsed via toIntOrNull (Int32)")
        val paramMax = getQueryParam("/v1/rides", "offset")["maximum"] as? Int
            ?: fail("/v1/rides offset parameter maximum missing")
        assertEquals(2147483647, paramMax, "offset query param must cap at Int32 (server toIntOrNull)")
    }
    @Test
    fun `verification submit request mirrors the server field validation - P6-1`() {
        assertEquals(64, int("VerificationSubmitRequest", "licenseNumber", "maxLength"))
        assertEquals(32, int("VerificationSubmitRequest", "vehiclePlate", "maxLength"))
        @Suppress("UNCHECKED_CAST")
        val docs = prop("VerificationSubmitRequest", "declaredDocs")
        assertEquals(1, docs["minItems"])
        assertEquals(16, docs["maxItems"])
        @Suppress("UNCHECKED_CAST")
        val item = docs["items"] as? Map<String, Any> ?: fail("declaredDocs.items missing")
        assertEquals(64, item["maxLength"])
    }

    @Test
    fun `verification reject reason mirrors the server 1 to 512 bound - P6-1`() {
        assertEquals(1, int("VerificationRejectRequest", "reason", "minLength"))
        assertEquals(512, int("VerificationRejectRequest", "reason", "maxLength"))
    }

    @Test
    fun `verification queue limit mirrors the server 1 to 200 clamp - P6-1`() {
        assertEquals(200, getQueryParam("/v1/admin/verification", "limit")["maximum"])
        assertEquals(50, getQueryParam("/v1/admin/verification", "limit")["default"])
        assertEquals(200, int("AdminVerificationList", "limit", "maximum"))
    }

    // ---------- P7-6 promo config contract alignment (round-34) ----------

    /**
     * The promo code item schema is an INLINE object inside PromoConfigSection (no
     * named component), so the codes items are navigated by hand. Every documented
     * bound must equal the PromoConfig.validationErrors rule (D49): schema-valid
     * accepted, schema-invalid rejected with 400 invalid_promos - the single write
     * path keeps the store in-bounds so GET can never echo schema-invalid config.
     */
    @Suppress("UNCHECKED_CAST")
    private val promoCodeProps: Map<String, Any> by lazy {
        val section = schemas["PromoConfigSection"] as? Map<String, Any> ?: fail("PromoConfigSection missing")
        val codes = (section["properties"] as Map<String, Any>)["codes"] as? Map<String, Any>
            ?: fail("PromoConfigSection.codes missing")
        assertEquals(50, codes["maxItems"], "server: MAX_CODES 50")
        val item = codes["items"] as? Map<String, Any> ?: fail("codes.items missing")
        item["properties"] as? Map<String, Any> ?: fail("codes.items.properties missing")
    }

    /**
     * The promo items carry ONE matching if/then per kind inside an allOf (round-35
     * structure; round-30 added the AMOUNT branch, round-35 the PERCENT one): the
     * kind-conditional server rules must be documented per kind exactly. `kind` is
     * required + enum [PERCENT, AMOUNT], so each branch is exact.
     */
    @Suppress("UNCHECKED_CAST")
    private fun promoKindBranch(kind: String): Map<String, Any> {
        val item = ((schemas["PromoConfigSection"] as Map<String, Any>)["properties"] as Map<String, Any>)["codes"]
            .let { (it as Map<String, Any>)["items"] as Map<String, Any> }
        val branches = item["allOf"] as? List<Map<String, Any>>
            ?: fail("codes.items.allOf (kind-conditional promo if/then branches) missing")
        return branches.firstOrNull { b ->
            val cond = b["if"] as? Map<String, Any>
            val k = (cond?.get("properties") as? Map<String, Any>)?.get("kind") as? Map<String, Any>
            (k?.get("const") as? String) == kind
        } ?: fail("codes.items.allOf has no if/then branch for kind $kind")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `promo bounds mirror the PromoConfig server validation - round-34`() {
        // perUserLimit 1..1000 (round-33: the schema maximum is enforced server-side).
        val perUser = promoCodeProps["perUserLimit"] as? Map<String, Any> ?: fail("perUserLimit missing")
        assertEquals(1, perUser["minimum"], "server: perUserLimit >= 1")
        assertEquals(1000, perUser["maximum"], "server: perUserLimit <= 1000")
        // value: the AMOUNT money cap is the wallet 1e18, documented inline exactly
        // like the round-24c wallet bodies and equal to PromoCodeConfig.MONEY_CAP.
        val value = promoCodeProps["value"] as? Map<String, Any> ?: fail("value missing")
        val valueMax = (value["maximum"] as? Long) ?: (value["maximum"] as? Int)?.toLong()
            ?: fail("value.maximum missing - contract drift (the server caps AMOUNT at 1e18)")
        assertEquals(PromoCodeConfig.MONEY_CAP, valueMax, "schema value cap must equal the server MONEY_CAP")
        // currency: maxLength 12 for ALL kinds - both on the shared property and in
        // the AMOUNT if/then (which only adds the minLength-1 requirement).
        val currency = promoCodeProps["currency"] as? Map<String, Any> ?: fail("currency missing")
        assertEquals(listOf("string", "null"), currency["type"], "currency is nullable in every kind")
        assertEquals(12, currency["maxLength"], "server: currency <= 12 chars for ALL promo kinds")
        val thenCurrency = (promoKindBranch("AMOUNT")["then"] as Map<String, Any>)["properties"]
            .let { (it as Map<String, Any>)["currency"] as? Map<String, Any> }
            ?: fail("AMOUNT then.currency missing")
        assertEquals(12, thenCurrency["maxLength"], "AMOUNT if/then keeps the 12-char bound")
        assertEquals(1, thenCurrency["minLength"], "AMOUNT if/then requires a non-empty currency")

        // Server-side mirror: PromoConfig.validationErrors flips EXACTLY at the
        // documented edges (schema-valid accepted, schema-invalid rejected).
        val base = PromoCodeConfig(
            code = "PINS34", kind = PromoCodeConfig.KIND_PERCENT, value = 10.0,
            validUntilEpochSec = 2_000_000_000L,
        )
        for (limit in 1..1000) {
            assertTrue(base.copy(perUserLimit = limit).validationErrors().isEmpty(), "perUserLimit $limit must be schema-valid AND accepted")
        }
        assertTrue(base.copy(perUserLimit = 1001).validationErrors().isNotEmpty(), "perUserLimit 1001 is schema-invalid AND rejected")
        assertTrue(base.copy(currency = "ABCDEFGHIJKL").validationErrors().isEmpty(), "12-char currency is schema-valid AND accepted")
        assertTrue(base.copy(currency = "ABCDEFGHIJKLM").validationErrors().isNotEmpty(), "13-char currency is schema-invalid AND rejected")
        // The AMOUNT cap on value: at the documented maximum, accepted; beyond -> rejected.
        val amountAtCap = PromoCodeConfig.MONEY_CAP.toDouble()
        assertTrue(
            base.copy(kind = PromoCodeConfig.KIND_AMOUNT, value = amountAtCap, currency = "PTS").validationErrors().isEmpty(),
            "AMOUNT value 1e18 (the schema maximum) is accepted",
        )
        assertTrue(
            base.copy(kind = PromoCodeConfig.KIND_AMOUNT, value = amountAtCap.nextUp(), currency = "PTS").validationErrors().isNotEmpty(),
            "the next double above the schema maximum is rejected (1e18 + 1.0 rounds back to the cap)",
        )
    }

    /**
     * Round-35 pin: the two remaining promo drifts are documented = enforced.
     * - PERCENT value cap (server: 0 < value <= 100, kind-conditional since round-30)
     *   as a matching if/then next to the AMOUNT one;
     * - the AMOUNT blank-rule (server isNullOrBlank) as the engine-proof whitespace
     *   pattern (DECISIONS D49, the displayName rule) on the if/then currency.
     * Both directions are pinned: schema-valid accepted, schema-invalid rejected.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `promo percent cap and currency whitespace pattern mirror the server - round-35`() {
        val base = PromoCodeConfig(
            code = "PINS35", kind = PromoCodeConfig.KIND_PERCENT, value = 10.0,
            validUntilEpochSec = 2_000_000_000L,
        )
        // PERCENT branch: value { type: number, exclusiveMinimum: 0, maximum: 100 }.
        val percentThen = promoKindBranch("PERCENT")["then"] as? Map<String, Any>
            ?: fail("codes.items.allOf[PERCENT].then (PERCENT value cap if/then) missing")
        val percentValue = (percentThen["properties"] as Map<String, Any>)["value"] as? Map<String, Any>
            ?: fail("PERCENT then.value missing")
        assertEquals(100.0, numberOf(percentValue, "maximum", "PromoConfigSection"), "server: PERCENT value <= 100 (100 itself is accepted)")
        val percentMin = (percentValue["exclusiveMinimum"] as? Number)?.toDouble()
            ?: fail("PERCENT then.value.exclusiveMinimum (numeric, OAS 3.1) missing")
        assertEquals(0.0, percentMin, "server rejects PERCENT value 0 and negatives (value <= 0.0)")
        for (v in listOf(1.0, 50.0, 99.999, 100.0)) {
            assertTrue(base.copy(value = v).validationErrors().isEmpty(), "PERCENT value $v is schema-valid AND accepted")
        }
        for (v in listOf(0.0, -1.0, 100.0.nextUp(), 150.0, 1e18)) {
            assertTrue(base.copy(value = v).validationErrors().isNotEmpty(), "PERCENT value $v is schema-invalid AND rejected")
        }

        // AMOUNT branch: the if/then currency carries the whitespace pattern.
        val thenCurrency = (promoKindBranch("AMOUNT")["then"] as Map<String, Any>)["properties"]
            .let { (it as Map<String, Any>)["currency"] as? Map<String, Any> }
            ?: fail("AMOUNT then.currency missing")
        val wsPattern = thenCurrency["pattern"] as? String
            ?: fail("AMOUNT if/then currency has no pattern - contract drift (the server blank-rule is undocumented)")
        val wsRe = Regex(wsPattern)
        assertTrue(!wsRe.containsMatchIn("   "), "whitespace-only currency is schema-invalid (unanchored search semantics)")
        assertTrue(wsRe.containsMatchIn("P"), "1-char currency stays schema-valid")
        assertTrue(wsRe.containsMatchIn("A B"), "whitespace AROUND non-blank content stays schema-valid")
        // The SHARED currency stays pattern-free: PERCENT is length-only server-side
        // (round-34), so "   " on a PERCENT code is schema-valid AND accepted - a
        // shared pattern would flip that into a ST201 negative_data_rejection.
        val sharedCurrency = promoCodeProps["currency"] as? Map<String, Any> ?: fail("currency missing")
        assertTrue(!sharedCurrency.containsKey("pattern"), "the SHARED currency must stay pattern-free (PERCENT has no blank rule)")
        val amountBase = base.copy(kind = PromoCodeConfig.KIND_AMOUNT, value = 500.0)
        assertTrue(amountBase.copy(currency = "P").validationErrors().isEmpty(), "1-char AMOUNT currency is schema-valid AND accepted")
        assertTrue(amountBase.copy(currency = "   ").validationErrors().isNotEmpty(), "whitespace-only AMOUNT currency is schema-invalid AND rejected")
        assertTrue(amountBase.copy(currency = "A B").validationErrors().isEmpty(), "spaced AMOUNT currency is schema-valid AND accepted")
        assertTrue(base.copy(currency = "   ").validationErrors().isEmpty(), "PERCENT + whitespace-only currency is schema-valid AND accepted (length-only rule)")
    }

    /**
     * P8-1 pin: the CancellationConfigSection bounds equal the CancellationConfig
     * server rules (documented = enforced, D49) - the single write path keeps the
     * store in-bounds so GET can never echo an out-of-bounds value.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `cancellation section bounds mirror the CancellationConfig server validation - P8-1`() {
        val section = schemas["CancellationConfigSection"] as? Map<String, Any> ?: fail("CancellationConfigSection missing")
        val props = section["properties"] as? Map<String, Any> ?: fail("CancellationConfigSection.properties missing")
        assertEquals(
            listOf("freeCancelWindowSec", "riderReasons", "driverReasons"),
            section["required"],
            "all three keys are schema-required (presence-strict decode, promos codes pattern)",
        )
        assertEquals(false, section["additionalProperties"], "additionalProperties false = unknown section keys are schema violations (documented = enforced)")

        val window = props["freeCancelWindowSec"] as? Map<String, Any> ?: fail("freeCancelWindowSec missing")
        assertEquals(0, window["minimum"], "server: freeCancelWindowSec >= 0 (minimum 0)")

        for (name in listOf("riderReasons", "driverReasons")) {
            val list = props[name] as? Map<String, Any> ?: fail("$name missing")
            assertEquals(20, list["maxItems"], "server: $name at most 20 reasons")
            val item = list["items"] as? Map<String, Any> ?: fail("$name.items missing")
            assertEquals(2, item["minLength"], "server: each reason >= 2 chars")
            assertEquals(120, item["maxLength"], "server: each reason <= 120 chars")
        }

        // Server-side mirror: validationErrors flips EXACTLY at the documented edges
        // (schema-valid accepted, schema-invalid rejected with 400 invalid_cancellation).
        val base = CancellationConfig()
        assertTrue(base.validationErrors().isEmpty(), "the bootstrap default is schema-valid")
        assertTrue(base.copy(freeCancelWindowSec = -1).validationErrors().isNotEmpty(), "freeCancelWindowSec -1 is schema-invalid AND rejected")
        assertTrue(base.copy(freeCancelWindowSec = 0).validationErrors().isEmpty(), "window 0 (minimum) is schema-valid AND accepted")
        val atBound = List(20) { i -> "reason-$i" }
        assertTrue(base.copy(riderReasons = atBound, driverReasons = atBound).validationErrors().isEmpty(), "20-entry catalogs (maxItems) are schema-valid AND accepted")
        assertTrue(base.copy(riderReasons = List(21) { i -> "reason-$i" }).validationErrors().isNotEmpty(), "21 rider reasons is schema-invalid AND rejected")
        assertTrue(base.copy(driverReasons = listOf("x")).validationErrors().isNotEmpty(), "1-char driver reason (minLength 2) is schema-invalid AND rejected")
        assertTrue(base.copy(riderReasons = listOf("a".repeat(121))).validationErrors().isNotEmpty(), "121-char rider reason (maxLength 120) is schema-invalid AND rejected")
        assertTrue(base.copy(riderReasons = listOf("a".repeat(120))).validationErrors().isEmpty(), "120-char rider reason is schema-valid AND accepted")
    }

    /**
     * P8-1 pin: the shared cancel path documents the 400/409 codes and the
     * CancelRequest 200-char cap; the /v1/config bundle component carries the
     * cancellation section (the CanonicalConfigDto equivalent); the admin
     * cancellation PATCH documents invalid_cancellation.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `cancel codes, CancelRequest cap and the bundle cancellation section - P8-1`() {
        // CancelRequest.reason: the 200-char per-request cap (trim happens server-side).
        assertEquals(200, int("CancelRequest", "reason", "maxLength"), "server: reason trimmed then capped at 200 chars (cancel_reason_too_long)")

        // The 400/409 codes are documented on the shared cancel path (rider AND driver
        // flows ride the same POST /v1/rides/{id}/cancel registration).
        val op = ((rootSpec["paths"] as? Map<String, Any>)?.get("/v1/rides/{id}/cancel") as? Map<String, Any>)
            ?.get("post") as? Map<String, Any> ?: fail("/v1/rides/{id}/cancel POST missing")
        val responses = op["responses"] as? Map<String, Any> ?: fail("cancel responses missing")
        for (code in listOf("400", "409")) {
            assertTrue(responses.containsKey(code), "$code must be documented on the cancel path (cancel_reason_* / cancel_not_allowed)")
        }
        val desc = op["description"] as? String ?: fail("cancel description missing")
        for (code in listOf("cancel_reason_not_allowed", "cancel_reason_required", "cancel_reason_too_long", "cancel_not_allowed")) {
            assertTrue(desc.contains(code), "the cancel path description documents $code verbatim")
        }
        // The optional-body contract: rider = optional, driver = always sends {reason}.
        assertTrue(desc.contains("OPTIONAL"), "the rider body is optional; the driver reason rule is documented in the description")

        // The /v1/config bundle component carries the cancellation section (the
        // CanonicalConfigDto equivalent: the canonical JSON string aggregates it).
        val bundleConfig = prop("ConfigBundle", "config")
        val bundleDesc = bundleConfig["description"] as? String ?: fail("ConfigBundle.config.description missing")
        assertTrue(bundleDesc.contains("cancellation"), "the canonical payload enumerates the cancellation section")

        // Admin section path: GET/PATCH with the documented invalid_cancellation 400.
        val patch = ((rootSpec["paths"] as? Map<String, Any>)?.get("/v1/admin/config/cancellation") as? Map<String, Any>)
            ?.get("patch") as? Map<String, Any> ?: fail("/v1/admin/config/cancellation PATCH missing")
        val patchResponses = patch["responses"] as? Map<String, Any> ?: fail("cancellation PATCH responses missing")
        assertTrue(patchResponses.containsKey("400"), "invalid_cancellation 400 documented")
        assertTrue((patch["description"] as? String ?: "").contains("invalid_cancellation"), "invalid_cancellation documented verbatim")
        assertTrue(patchResponses.containsKey("415"), "415 mirrors the other config sections")
    }

    /**
     * Round-36c pin: duplicate promo codes. JSON Schema cannot express per-field
     * uniqueness, so:
     * - whole-item duplicates are schema-INVALID via uniqueItems on the codes array;
     * - distinct-object duplicate-code documents stay schema-valid and the PATCH
     *   answers the DOCUMENTED 409 duplicate_promo_code (ST202-compatible: 409 is
     *   in the accepted status set, 400 is not - case Mot8It).
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `promo code uniqueness - uniqueItems documented + 409 duplicate_promo_code - round-36c`() {
        val codesProp = ((schemas["PromoConfigSection"] as Map<String, Any>)["properties"] as Map<String, Any>)["codes"]
            as? Map<String, Any> ?: fail("PromoConfigSection.codes missing")
        assertEquals(true, codesProp["uniqueItems"], "whole-item duplicates are schema-invalid (uniqueItems)")

        val patch = ((rootSpec["paths"] as? Map<String, Any>)?.get("/v1/admin/config/promos") as? Map<String, Any>)
            ?.get("patch") as? Map<String, Any> ?: fail("/v1/admin/config/promos PATCH missing")
        val responses = patch["responses"] as? Map<String, Any> ?: fail("PATCH responses missing")
        assertTrue(responses.containsKey("409"), "409 duplicate_promo_code must be documented on the PATCH path")

        // Server mirror: validationErrors reports each duplicated code exactly once.
        val dupes = listOf(
            PromoCodeConfig(code = "DUP", kind = PromoCodeConfig.KIND_PERCENT, value = 10.0, validUntilEpochSec = 2_000_000_000L),
            PromoCodeConfig(code = "DUP", kind = PromoCodeConfig.KIND_PERCENT, value = 20.0, validUntilEpochSec = 2_000_000_000L),
        )
        val errs = PromoConfig(dupes).validationErrors()
        assertEquals(
            listOf("codes: duplicate code DUP"),
            errs.filter { it.startsWith("codes: duplicate") },
            "each duplicated code value is reported exactly once",
        )
    }
}


