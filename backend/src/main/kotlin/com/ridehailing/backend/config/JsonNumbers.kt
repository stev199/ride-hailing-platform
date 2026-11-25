package com.ridehailing.backend.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.floor

/**
 * JSON Schema "integer" semantics (draft 2020-12): a number with a zero
 * fractional part IS an integer, so `0.0`, `3.0` or `1e3` are schema-valid
 * values for `type: integer` and must not be rejected (schemathesis ST202
 * finding, P1-CONTRACT round 5: kotlinx.serialization is notation-strict and
 * refused `priority: 0.0` with 400 invalid_body).
 *
 * Integer patch fields parse through this serializer:
 * - JSON null -> null (patch no-op)
 * - integral numbers within Int32 -> their Int value
 * - everything else (2.5, "0" as a string, 2147483648.0) still fails the body
 *   parser -> 400 invalid_body (documented; the spec pins maximum Int32 via
 *   ContractAlignmentTest).
 */
object ZeroFractionIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ZeroFractionInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val dec = decoder as? JsonDecoder
            ?: throw SerializationException("integer fields require JSON transport")
        return when (val el = dec.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (el.isString) throw SerializationException("integer field must be a number")
                el.intOrNull
                    ?: el.doubleOrNull
                        ?.takeIf { it == floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
                        ?.toInt()
                    ?: throw SerializationException("integer field must be an Int32 number")
            }
            else -> throw SerializationException("integer field must be a number")
        }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/**
 * Non-null twin of [ZeroFractionIntSerializer] for REQUIRED integer fields
 * (P4-1 fare config hour windows): JSON null and integral floats in Int32
 * range parse per JSON Schema "integer" semantics, everything else fails the
 * body parser (400 invalid_body, documented).
 */
object ZeroFractionIntRequiredSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ZeroFractionIntRequired", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int =
        when (val el = (decoder as? JsonDecoder)?.decodeJsonElement()) {
            is JsonNull -> throw SerializationException("integer field must be a number")
            is JsonPrimitive -> {
                if (el.isString) throw SerializationException("integer field must be a number")
                el.intOrNull
                    ?: el.doubleOrNull
                        ?.takeIf { it == floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
                        ?.toInt()
                    ?: throw SerializationException("integer field must be an Int32 number")
            }
            else -> throw SerializationException("integer field must be a number")
        }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}
