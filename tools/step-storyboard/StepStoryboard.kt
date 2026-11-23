package com.ridehailing.test.storyboard

import android.graphics.Bitmap
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
 * must be wrapped in [step]; a test-rule fails classes with zero step() calls.
 *
 * Files: <run>/<app>/<shard>/<test>/<NN>-<slug>.png
 * Viewer: POST http://10.0.2.2:7070/api/step  (host-side Test Viewer, E4)
 */
object StepStoryboard {
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val client =
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private var runId: String = System.getenv("RUN_ID") ?: "local"
    private var app: String = "unknown"
    private var shard: Int = 0
    private var testId: String = ""
    private var testName: String = ""
    private var counter: Int = 0
    private var artifactDir: File = File("/data/local/tmp/storyboard")
    private var viewerBase: String = System.getenv("VIEWER_URL") ?: "http://10.0.2.2:7070"

    fun begin(testId: String, testName: String, app: String, shard: Int, artifactDir: File) {
        this.testId = testId; this.testName = testName; this.app = app; this.shard = shard
        this.artifactDir = artifactDir; this.counter = 0
        artifactDir.mkdirs()
    }

    /** Wrap a meaningful test action; captures after-shot (and before-shot if before=true). */
    fun step(name: String, before: Boolean = false, block: () -> Unit) {
        if (before) capture(name, isBefore = true)
        val failedInitially = try { block(); false } catch (t: Throwable) { capture(name, failed = true); throw t }
        if (!failedInitially) capture(name, isBefore = false)
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

    fun finish(status: String) = post(testId, testName, app, shard, step = null, screenshotB64 = null, event = "test-end", status = status)

    private fun capture(slug: String, isBefore: Boolean = false, failed: Boolean = false) {
        val tag = when {
            failed -> "FAIL"
            isBefore -> "pre"
            else -> "%02d".format(counter)
        }
        val file = File(artifactDir, "$tag-${slug.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.png")
        runCatching {
            val img: Bitmap = device.takeScreenshot()
            img.compress(Bitmap.CompressFormat.PNG, 85, file.outputStream())
            lastFile = file
        }
    }

    private var lastFile: File? = null
    private fun lastFileB64(): String? = lastFile?.takeIf { it.exists() }?.let {
        Base64.getEncoder().encodeToString(it.readBytes())
    }

    private fun dumpDiagnostics(reason: String) {
        runCatching {
            File(artifactDir, "FAIL-hierarchy.xml").writeText(device.dumpWindowHierarchy())
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
            client.newCall(req).execute().use { it.close() }
        }
    }
}
