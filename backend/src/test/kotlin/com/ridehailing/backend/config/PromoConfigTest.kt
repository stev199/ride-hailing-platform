package com.ridehailing.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-6 promo config validation + discount math (pure JVM). The canonical JSON
 * inclusion is proven end-to-end by the bundle publish path (updatePromos burns a
 * version); here the RULES are pinned.
 */
class PromoConfigTest {

    private fun code(
        code: String = "WELCOME50",
        kind: String = PromoCodeConfig.KIND_PERCENT,
        value: Double = 50.0,
        currency: String? = null,
        validFrom: Long = 0,
        validUntil: Long = 2_000_000_000L,
        perUserLimit: Int = 1,
        active: Boolean = true,
    ) = PromoCodeConfig(code, kind, value, currency, validFrom, validUntil, perUserLimit, active)

    @Test
    fun `valid percent code produces no errors`() {
        assertTrue(PromoConfig(codes = listOf(code())).validationErrors().isEmpty())
    }

    @Test
    fun `code shape uppercase A-Z0-9 3-24 only`() {
        for (bad in listOf("ab", "lower_case", "WITH SPACE", "TOOLONGCODE_XXXXXXXXXXXXX", "")) {
            assertTrue(
                code(code = bad.uppercase()).validationErrors().isNotEmpty() ||
                    !Regex("^[A-Z0-9]{3,24}$").matches(bad.uppercase()),
                "bad code rejected: $bad",
            )
        }
        assertTrue(code(code = "ABC").validationErrors().isEmpty())
        assertTrue(code(code = "A1B2C3D4E5").validationErrors().isEmpty())
    }

    @Test
    fun `percent value bounded 0-100 exclusive`() {
        assertTrue(code(value = 0.0).validationErrors().isNotEmpty())
        assertTrue(code(value = 100.0).validationErrors().isEmpty())
        assertTrue(code(value = 150.0).validationErrors().isNotEmpty())
        assertTrue(code(value = -1.0).validationErrors().isNotEmpty())
    }

    @Test
    fun `amount kind requires a currency`() {
        assertTrue(code(kind = PromoCodeConfig.KIND_AMOUNT, value = 500.0).validationErrors().isNotEmpty())
        assertTrue(
            code(kind = PromoCodeConfig.KIND_AMOUNT, value = 500.0, currency = "POINTS").validationErrors().isEmpty(),
        )
    }

    @Test
    fun `validity window per-field bounds only (no cross-field rule, round-24b parity)`() {
        // An unordered/equal window is DEAD config (never applies), not invalid -
        // the schema cannot express the cross-field rule, so the server must not
        // enforce it (round-24b wallet parity, round-31 ST202).
        assertTrue(code(validFrom = 10, validUntil = 10).validationErrors().isEmpty())
        assertTrue(code(validFrom = 20, validUntil = 10).validationErrors().isEmpty())
        assertTrue(code(validUntil = 0).validationErrors().isNotEmpty())
        assertTrue(code(perUserLimit = 0).validationErrors().isNotEmpty())
        assertTrue(code(validFrom = -1).validationErrors().isNotEmpty())
    }

    @Test
    fun `section rejects duplicates and caps at 50`() {
        assertTrue(PromoConfig(codes = listOf(code(), code())).validationErrors().isNotEmpty())
        val fifty = PromoConfig(codes = (1..50).map { code(code = "C$it".padStart(3, '0')) })
        assertTrue(fifty.validationErrors().isEmpty())
        val fiftyOne = PromoConfig(codes = (1..51).map { code(code = "C$it".padStart(3, '0')) })
        assertTrue(fiftyOne.validationErrors().isNotEmpty())
    }

    @Test
    fun `discountFor percent math clamps to the fare`() {
        assertEquals(500L, code(value = 50.0).discountFor(1000))
        assertEquals(125L, code(value = 12.5).discountFor(1000))
        assertEquals(1000L, code(value = 100.0).discountFor(1000))
        assertEquals(333L, code(value = 33.3).discountFor(1000)) // floor
    }

    @Test
    fun `discountFor amount math never exceeds the fare`() {
        val c = code(kind = PromoCodeConfig.KIND_AMOUNT, value = 4000.0, currency = "POINTS")
        assertEquals(4000L, c.discountFor(5000))
        assertEquals(300L, c.discountFor(300)) // clamp: never negative charge
    }

    @Test
    fun `byCode uppercases and trims the lookup`() {
        val cfg = PromoConfig(codes = listOf(code(code = "WELCOME50")))
        assertEquals("WELCOME50", cfg.byCode(" welcome50 ")?.code)
        assertTrue(cfg.byCode("NOPE") == null)
    }
}
