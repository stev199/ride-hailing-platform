package com.ridehailing.backend

/**
 * Round-37 (schemathesis case IbYD7y, run 46134608138): JSON Schema maxLength /
 * minLength count UNICODE CODE POINTS; Kotlin String.length counts UTF-16 code
 * units. A string with surrogate pairs (e.g. 9 digits + 2 astral characters =
 * 11 code points but 13 units) is schema-valid at maxLength 12, while a
 * `.length > 12` check 400s it - ST202 "schema-valid request rejected". The
 * mirror drift also exists on minLength (a 2-code-point string can be 3-4
 * units, so a `.length < 2` check would REJECT a schema-valid string).
 *
 * Every openapi-documented length bound in this backend MUST count code points
 * (PasswordService set the precedent for password bounds). `.length` checks on
 * documented bounds are contract drift and are caught by the contract
 * alignment suite + schemathesis boundary cases.
 */
fun String.lengthInCodePoints(): Int = codePointCount(0, length)
