package com.ridehailing.backend

import com.ridehailing.backend.auth.Identifiers
import com.ridehailing.backend.auth.PasswordService
import org.yaml.snakeyaml.Yaml
import java.io.File
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
        // Payment method: CASH-only enum (server: payment_method_unavailable otherwise).
        @Suppress("UNCHECKED_CAST")
        val pm = prop("RideCreateRequest", "paymentMethod")["enum"] as List<*>
        assertEquals(listOf("CASH"), pm)
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
    fun `verification reject reason mirrors the server 1..512 bound - P6-1`() {
        assertEquals(1, int("VerificationRejectRequest", "reason", "minLength"))
        assertEquals(512, int("VerificationRejectRequest", "reason", "maxLength"))
    }

    @Test
    fun `verification queue limit mirrors the server 1..200 clamp - P6-1`() {
        assertEquals(200, getQueryParam("/v1/admin/verification", "limit")["maximum"])
        assertEquals(50, getQueryParam("/v1/admin/verification", "limit")["default"])
        assertEquals(200, int("AdminVerificationList", "limit", "maximum"))
    }
