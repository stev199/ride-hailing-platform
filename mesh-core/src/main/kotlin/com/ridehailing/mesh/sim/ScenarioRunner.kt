package com.ridehailing.mesh.sim

import java.io.File
import kotlin.system.exitProcess

/**
 * GATE 3 runner (Part C): the 15 mandatory scenarios + the 4 GATE 2 close-out
 * scenarios (16-19) on fixed seeds; baseline.json regression gate (>10%
 * regression fails the build); evidence output to /docs/evidence/mesh-sim
 * (metrics table + charts) via [EvidenceWriter].
 */
object ScenarioSuite {
    fun runAll(): List<ScenarioResult> = listOf(
        Scenarios.lineLossless(),
        Scenarios.lineBeyondMaxHops(),
        Scenarios.starGridFlood(),
        Scenarios.ringNoCirculation(),
        Scenarios.broadcastStorm(),
        Scenarios.lossyLinks(),
        ScenariosAdvanced.staticMultiPathOracle(),
        Scenarios.relayFailureMidReply(),
        ScenariosAdvanced.mobility(),
        ScenariosAdvanced.partitionHeal(),
        ScenariosAdvanced.gatewayChurn(),
        ScenariosAdvanced.maliciousNodes(),
        ScenariosAdvanced.configChangeLive(),
        ScenariosAdvanced.clockSkew(),
        ScenariosAdvanced.fragmentationLossReordering(),
        ScenariosGate2.driverBeacons(),
        ScenariosGate2.presenceToGateway(),
        ScenariosGate2.internetShortcut(),
        ScenariosGate2.gatewayOpacityAndReplay(),
    )
}

/** Runs the suite once per JVM (tests and the report task share one execution). */
object SuiteCache {
    @Volatile
    private var cached: List<ScenarioResult>? = null

    fun results(): List<ScenarioResult> =
        cached ?: synchronized(this) { cached ?: ScenarioSuite.runAll().also { cached = it } }
}

/**
 * Baseline regression gate: for every metric present in both current results and
 * baseline.json, a deviation beyond [tolerance] (10% per GATE 3) in the harmful
 * direction fails. EXACT metrics must match outright (the suite is seeded).
 */
object BaselineCheck {

    fun compare(results: List<ScenarioResult>, baseline: Map<String, Map<String, Double>>, tolerance: Double = 0.10): List<String> {
        val out = mutableListOf<String>()
        for (r in results) {
            val base = baseline[r.name] ?: continue // new scenario: captured on next baseline update
            for ((metric, current) in r.metrics) {
                val baseVal = base[metric] ?: continue
                when (MetricDirections.directionOf(metric)) {
                    MetricDirection.HIGHER_IS_BETTER ->
                        if (current < baseVal * (1 - tolerance)) out.add("${r.name}.$metric regressed: $current < baseline $baseVal")
                    MetricDirection.LOWER_IS_BETTER ->
                        if (current > baseVal * (1 + tolerance)) out.add("${r.name}.$metric regressed: $current > baseline $baseVal")
                    MetricDirection.EXACT ->
                        if (current != baseVal) out.add("${r.name}.$metric changed: $current != baseline $baseVal")
                }
            }
        }
        return out
    }

    fun toJson(results: List<ScenarioResult>): String = buildString {
        append("{\n  \"scenarios\": [\n")
        results.forEachIndexed { i, r ->
            append("    {\n      \"name\": ${jsonStr(r.name)},\n      \"metrics\": {\n")
            r.metrics.entries.forEachIndexed { j, (k, v) ->
                append("        ${jsonStr(k)}: ${jsonNum(v)}${if (j < r.metrics.size - 1) "," else ""}\n")
            }
            append("      }\n    }${if (i < results.size - 1) "," else ""}\n")
        }
        append("  ]\n}\n")
    }

    /** Minimal JSON reader for the exact structure this module writes (no dependency). */
    fun parse(text: String): Map<String, Map<String, Double>> {
        val p = Parser(text)
        val root = p.readValue() as Map<*, *>
        val scenarios = root["scenarios"] as List<*>
        val out = linkedMapOf<String, Map<String, Double>>()
        for (s in scenarios) {
            val m = s as Map<*, *>
            val name = m["name"] as String
            val metricsRaw = m["metrics"] as Map<*, *>
            out[name] = metricsRaw.entries.associate { (k, v) -> (k as String) to when (v) {
                is Number -> v.toDouble()
                else -> (v as String).toDouble()
            } }
        }
        return out
    }

    private fun jsonStr(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun jsonNum(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "0" else if (v == v.toLong().toDouble()) "${v.toLong()}.0" else v.toString()

    private class Parser(val text: String) {
        var pos = 0
        fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun readValue(): Any? {
            skipWs()
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                else -> readLiteral()
            }
        }
        fun readObject(): Map<String, Any?> {
            skipWs(); pos++ // {
            val out = linkedMapOf<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs(); check(text[pos] == ':') { "expected : at $pos" }; pos++
                out[key] = readValue()
                skipWs()
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> check(false) { "expected , or } at $pos" }
                }
            }
        }
        fun readArray(): List<Any?> {
            skipWs(); pos++ // [
            val out = mutableListOf<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> check(false) { "expected , or ] at $pos" }
                }
            }
        }
        fun readString(): String {
            skipWs(); check(text[pos] == '"'); pos++
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') { pos++; sb.append(text[pos - 1]) } else sb.append(c)
            }
        }
        fun readLiteral(): Any {
            skipWs()
            val start = pos
            while (pos < text.length && text[pos] !in ",]} \n\r\t") pos++
            val s = text.substring(start, pos)
            return s.toDoubleOrNull() ?: s
        }
    }
}

/**
 * CLI: runs the suite, writes evidence, applies the baseline gate.
 * Exits 1 on threshold failures, 2 on baseline regressions.
 *
 *   ScenarioRunnerKt [--evidence <dir>] [--baseline <file>] [--update-baseline]
 */
fun main(args: Array<String>) {
    var evidenceDir = "../docs/evidence/mesh-sim"
    var baselineFile = "baseline.json"
    var updateBaseline = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--evidence" -> evidenceDir = args[++i]
            "--baseline" -> baselineFile = args[++i]
            "--update-baseline" -> updateBaseline = true
            else -> error("unknown arg ${args[i]}")
        }
        i++
    }

    val results = ScenarioSuite.runAll()
    val thresholdFailures = results.flatMap { r -> r.failures().map { "${r.name}: $it" } }
    EvidenceWriter.writeAll(results, File(evidenceDir))

    val baselineFileHandle = File(baselineFile)
    if (updateBaseline || !baselineFileHandle.exists()) {
        baselineFileHandle.writeText(BaselineCheck.toJson(results))
        println("baseline ${if (updateBaseline) "updated" else "initialized"} at ${baselineFileHandle.absolutePath}")
    } else {
        val regressions = BaselineCheck.compare(results, BaselineCheck.parse(baselineFileHandle.readText()))
        if (regressions.isNotEmpty()) {
            println("BASELINE REGRESSIONS (${regressions.size}):")
            regressions.forEach { println("  - $it") }
            exitProcess(2)
        }
        println("baseline check: no regressions vs ${baselineFileHandle.absolutePath}")
    }

    if (thresholdFailures.isNotEmpty()) {
        println("SCENARIO THRESHOLD FAILURES (${thresholdFailures.size}):")
        thresholdFailures.forEach { println("  - $it") }
        exitProcess(1)
    }
    println("GATE 3 scenario suite: all ${results.size} scenarios PASS")
}
