package com.ridehailing.test.storyboard

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Part E2 test rule: wires each UI test into the Step Storyboard pipeline.
 *
 * - begin/finish events around the whole test; declare this rule AFTER the compose rule
 *   (higher @get:Rule order) so it wraps it: begin runs before the activity launch and
 *   the FAIL capture sees compose assertion failures.
 * - on failure: FAIL screenshot + view hierarchy dump + logcat tail into the artifact dir.
 * - E2 enforcement: a test that finishes with ZERO step() calls fails with an
 *   AssertionError ("the storyboard must read like a story").
 *
 * Artifact layout: <app-external-files>/storyboard/<test-slug>/<NN>-<step-slug>.png;
 * runId (runId instrumentation arg -> RUN_ID env -> "local") and shard are recorded via
 * the viewer events and the CI out-dir naming, keeping the device path shallow enough
 * for the gallery walk depth limit (tools/report/generate.js).
 */
class StoryboardRule(private val app: String, private val shard: Int = -1) : TestRule {

    /** Convenience passthrough so tests read `storyboard.step("...") { ... }`. */
    fun step(name: String, before: Boolean = false, block: () -> Unit) =
        StepStoryboard.step(name, before, block)

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val args: Bundle = InstrumentationRegistry.getArguments()
            val effectiveShard = if (shard >= 0) shard
            else (args.getString("shardIndex")?.toIntOrNull() ?: 0)
            val testId = "${description.testClass.simpleName}#${description.methodName}"
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(
                ctx.getExternalFilesDir(null) ?: ctx.filesDir,
                "storyboard/${Slug.of(testId)}",
            )
            StepStoryboard.begin(testId, description.methodName, app, effectiveShard, dir)
            var failed = false
            try {
                base.evaluate()
            } catch (t: Throwable) {
                failed = true
                StepStoryboard.fail(t.message ?: t.javaClass.simpleName)
                throw t
            } finally {
                // Persist before finish: AGP uninstalls the app after connected tests and
                // wipes its external files dir (see StepStoryboard.persist).
                StepStoryboard.persist()
                StepStoryboard.finish(if (failed) "failed" else "passed")
            }
            check(StepStoryboard.stepCount > 0) {
                "E2 violation: '$testId' finished without a single step() call - " +
                    "wrap each meaningful action in step(\"...\") { ... }"
            }
        }
    }
}
