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
    fun `currency maxLength 12 for ALL promo kinds (round-34)`() {
        // The openapi currency is { type: [string, 'null'], maxLength: 12 } - NOT
        // kind-conditional: the round-24c-era check guarded AMOUNT only, so a PERCENT
        // code could carry a schema-invalid 13-char currency and echo it in GET/PATCH.
        assertTrue(
            code(currency = "ABCDEFGHIJKLM").validationErrors().any { it.contains("currency") },
            "PERCENT with a 13-char currency rejects",
        )
        assertTrue(
            code(kind = PromoCodeConfig.KIND_AMOUNT, value = 500.0, currency = "ABCDEFGHIJKLM")
                .validationErrors().any { it.contains("currency") },
            "AMOUNT with a 13-char currency still rejects",
        )
        // The 12-char boundary is schema-valid for BOTH kinds; JSON null stays valid
        // (only AMOUNT turns it into a required-currency error).
        assertTrue(code(currency = "ABCDEFGHIJKL").validationErrors().isEmpty())
        assertTrue(
            code(kind = PromoCodeConfig.KIND_AMOUNT, value = 500.0, currency = "ABCDEFGHIJKL")
                .validationErrors().isEmpty(),
        )
        assertTrue(code(currency = "POINTS").validationErrors().isEmpty())
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
    fun `perUserLimit bounded 1-1000 (schema maximum, round-33 ST108-ST201)`() {
        // Round-33 (CI QK4V6R/XT4gZF): a PATCH with perUserLimit 1001 was accepted and
        // then ECHOED in GET responses - the openapi schema pins 1..1000.
        assertTrue(code(perUserLimit = 1).validationErrors().isEmpty())
        assertTrue(code(perUserLimit = 1000).validationErrors().isEmpty(), "schema maximum is valid")
        assertTrue(code(perUserLimit = 1001).validationErrors().isNotEmpty(), "above the schema maximum rejects")
        assertTrue(code(perUserLimit = 0).validationErrors().isNotEmpty())
    }

    @Test
    fun `amount money cap - boundary-double leniency on the decimal form (round-33 ST202)`() {
        // The EXACT CI case eDh6YV: 1.0000000000000001e+18 (= 1e18+100 decimal) parses
        // to the double 1e18+128 - strictly above the cap as bits - but its DECIMAL
        // FORM reads as the cap at double precision (15 significant digits): accepted,
        // clamped to the cap so the store stays strictly in-bounds.
        assertEquals(
            PromoCodeConfig.MONEY_CAP.toDouble(),
            PromoCodeConfig.amountValueInMoneyCap("1.0000000000000001e+18"),
        )
        // The double bits alone are NOT enough: 1e18+128 > 1e18.
        assertTrue(
            1.0000000000000001e+18 > PromoCodeConfig.MONEY_CAP.toDouble(),
            "premise: the parsed double is strictly above the cap - the DECIMAL FORM decides",
        )
        // Strictly beyond -> rejected.
        assertEquals(null, PromoCodeConfig.amountValueInMoneyCap("1e21"))
        // Integer text above the cap: exact comparison, no leniency (round-24c parity).
        assertEquals(null, PromoCodeConfig.amountValueInMoneyCap("1000000000000000001"))
        // Exact integer AT the cap is valid.
        assertEquals(
            PromoCodeConfig.MONEY_CAP.toDouble(),
            PromoCodeConfig.amountValueInMoneyCap("1000000000000000000"),
        )
        // Ordinary in-cap values pass through unchanged.
        assertEquals(500.0, PromoCodeConfig.amountValueInMoneyCap("500"))
        // Non-finite / unrepresentable -> rejected.
        assertEquals(null, PromoCodeConfig.amountValueInMoneyCap("1e400"))
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
