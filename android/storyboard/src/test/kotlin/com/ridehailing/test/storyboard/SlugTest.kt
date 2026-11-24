package com.ridehailing.test.storyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM unit tests for the storyboard file-name slug (P2-3; no android deps). */
class SlugTest {
    @Test fun lowercasesAndHyphenates() = assertEquals("tap-request-ride", Slug.of("Tap Request Ride"))

    @Test fun consecutiveNonAlnumCollapseToSingleDash() = assertEquals("a-b-c", Slug.of("a!!!b??c"))

    @Test fun leadingAndTrailingDashesTrimmed() = assertEquals("launch", Slug.of("-- Launch **"))

    @Test fun digitsAreKept() = assertEquals("shard-0-home", Slug.of("Shard 0 HOME"))

    @Test fun emptyFallsBackToStep() = assertEquals("step", Slug.of("///"))

    @Test fun testIdSlugsAreUrlSafe() =
        assertEquals("riderlaunchinstrumentedtest-shelllaunches", Slug.of("RiderLaunchInstrumentedTest#shellLaunches"))
}
