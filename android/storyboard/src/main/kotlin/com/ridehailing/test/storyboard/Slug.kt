package com.ridehailing.test.storyboard

/**
 * Filesystem-safe slug for storyboard step names (E2 layout: <NN>-<slug>.png).
 * Pure Kotlin so it is unit-testable on the JVM without touching android classes.
 */
object Slug {
    private val nonAlnum: Regex = Regex("[^a-z0-9]+")

    fun of(name: String): String =
        name.lowercase().replace(nonAlnum, "-").trim('-').ifEmpty { "step" }
}
