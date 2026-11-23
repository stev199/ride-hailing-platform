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
    private val schemas: Map<String, Any> by lazy {
        val candidates = listOf(File("../docs/api/openapi.yaml"), File("docs/api/openapi.yaml"))
        val file = candidates.firstOrNull { it.isFile }
            ?: fail("openapi.yaml not found relative to ${File("").absolutePath}")
        val root = Yaml().load(file.inputStream()) as Map<String, Any>
        val components = root["components"] as Map<String, Any>
        components["schemas"] as Map<String, Any>
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
}
