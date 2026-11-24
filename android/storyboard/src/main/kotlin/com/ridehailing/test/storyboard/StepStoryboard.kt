package com.ridehailing.test.storyboard

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Part E2 Step Storyboard helper (androidTest). Every meaningful action in a UI test
 * must be wrapped in [step]; [StoryboardRule] fails tests that finish with zero steps.
 *
 * Files: <artifactDir>/<NN>-<slug>.png where the rule resolves artifactDir to
 * <app-external-files>/storyboard/<test-slug>/ (collect-artifacts.sh pulls it into the
 * run's report gallery; the gallery walk has a depth limit, so the path stays shallow
 * and slug-safe - no '#', no spaces).
 *
 * Viewer: POST http://10.0.2.2:7070/api/step (host-side Test Viewer, E4) - best effort,
 * an unavailable viewer must never fail a test.
 */
object StepStoryboard {
    private val device: UiDevice by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }
    private val client =
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private var app: String = "unknown"
    private var shard: Int = 0
    private var testId: String = ""
    private var testName: String = ""
    private var counter: Int = 0
    private var artifactDir: File = File("/data/local/tmp/storyboard")
    private var viewerBase: String =
        InstrumentationRegistry.getArguments().getString("viewerUrl")
            ?: System.getenv("VIEWER_URL")
            ?: "http://10.0.2.2:7070"

    /** Number of step() calls made in the current test (zero-step enforcement, E2). */
    val stepCount: Int get() = counter

    fun begin(testId: String, testName: String, app: String, shard: Int, artifactDir: File) {
        this.testId = testId; this.testName = testName; this.app = app; this.shard = shard
        this.artifactDir = artifactDir; this.counter = 0
        artifactDir.mkdirs()
    }

    /** Wrap a meaningful test action; captures after-shot (and before-shot if before=true). */
    fun step(name: String, before: Boolean = false, block: () -> Unit) {
        if (before) capture(name, isBefore = true)
        try {
            block()
        } catch (t: Throwable) {
            capture(name, failed = true)
            throw t
        }
        capture(name)
        post(testId, testName, app, shard, name, lastFileB64())
        counter++
    }

    /** Assert with storyboard-aware failure: dumps hierarchy + last 200 logcat lines. */
    fun check(message: String, condition: () -> Boolean) {
        if (!condition()) {
            dumpDiagnostics(message)
            assertTrue("step-check failed: $message", false)
        }
    }

    /** Failure evidence for a test-level failure: FAIL screenshot + hierarchy + logcat. */
    fun fail(reason: String) {
        capture("failure", failed = true)
        dumpDiagnostics(reason)
    }

    fun finish(status: String) =
        post(testId, testName, app, shard, step = null, screenshotB64 = null, event = "test-end", status = status)

    /**
     * UiAutomation shell commands run as the APP uid on aosp_atd (empty output, no effect;
     * SELinux denies app writes to /data/local/tmp) - the old persist-to-/data/local/tmp
     * bridge cannot work. Evidence survives via run-ui-tests.sh's poll-pull loop, which
     * drains the app's external files dir WHILE the tests run, before the post-test
     * uninstall wipes it. Kept as a hook only (no-op here).
     */
    fun persist() {
        println("[storyboard] persist: no-op (poll-pull collects during the run; UiAutomation shell = app uid on this image)")
    }

    private fun capture(slug: String, isBefore: Boolean = false, failed: Boolean = false) {
        val tag = when {
            failed -> "FAIL"
            isBefore -> "pre"
            else -> "%02d".format(counter)
        }
        val name = "$tag-${Slug.of(slug)}.png"
        // PRIMARY: in-process UiDevice screenshot into the app's own external files dir.
        // PROVEN writable on aosp_atd (runs 45473604435/45476940195/45479391089 logcat:
        // UiDevice stores the PNG there), while UiAutomation shell commands run as the APP
        // uid on this image (empty output, no effect - SELinux denies /data/local/tmp), so
        // shell-level screencap/mkdir/cp bridges cannot work. run-ui-tests.sh pulls the
        // dir continuously WHILE the tests run (poll-pull), before AGP's post-test
        // uninstall wipes Android/data/<pkg>.
        val file = File(artifactDir, name)
        val stored = runCatching { device.takeScreenshot(file, 1.0f, 85) }.getOrDefault(false)
        if (stored && file.exists()) {
            lastFile = file
        } else {
            println("[storyboard] capture failed for $name (stored=$stored)")
            lastFile = null
        }
    }

    private var lastFile: File? = null
    private fun lastFileB64(): String? = lastFile?.takeIf { it.exists() }?.let {
        Base64.getEncoder().encodeToString(it.readBytes())
    }

    private fun dumpDiagnostics(reason: String) {
        runCatching {
            device.dumpWindowHierarchy(File(artifactDir, "FAIL-hierarchy.xml"))
            device.executeShellCommand("logcat -d -t 200").let {
                File(artifactDir, "FAIL-logcat.txt").writeText(it)
            }
            File(artifactDir, "FAIL-reason.txt").writeText(reason)
        }
    }

    private fun post(
        testId: String, test: String, app: String, shard: Int,
        step: String?, screenshotB64: String?, event: String? = null, status: String? = null,
    ) {
        runCatching { // viewer unavailable must never fail a test
            val body = buildString {
                append("{\"testId\":\"").append(testId).append("\",\"test\":\"").append(test)
                append("\",\"app\":\"").append(app).append("\",\"shard\":").append(shard)
                if (step != null) append(",\"step\":\"").append(step.replace("\"", "'")).append("\"")
                if (screenshotB64 != null) append(",\"screenshot\":\"").append(screenshotB64).append("\"")
                if (event != null) append(",\"event\":\"").append(event).append("\"")
                if (status != null) append(",\"status\":\"").append(status).append("\"")
                append("}")
            }
            val req = Request.Builder().url("$viewerBase/api/step")
                .post(body.toRequestBody(json)).build()
            client.newCall(req).execute().use { resp ->
                println("[storyboard] POST ${resp.code} -> $viewerBase (testId=$testId, step=$step)")
            }
        }.onFailure {
            println("[storyboard] POST failed -> $viewerBase: ${it.message?.take(200)}")
        }
    }
}
