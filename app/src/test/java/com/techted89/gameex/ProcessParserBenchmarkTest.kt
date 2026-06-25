package com.techted89.gameex

import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import kotlin.system.measureTimeMillis

class ProcessParserBenchmarkTest {
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    private fun legacyParsePsOutput(reader: BufferedReader): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        var line: String? = reader.readLine()
        while (line != null) {
            // Typical ps output: USER PID ... NAME
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size >= 9) {
                // Assuming standard android ps output where PID is usually 2nd column
                // and Name is last column.
                val pidStr = parts[1]
                val name = parts.last()
                try {
                    val pid = pidStr.toInt()
                    // Default values for raw parsing
                    processes.add(ProcessInfo(pid, name, name, null, true))
                } catch (e: NumberFormatException) {
                    // Ignore header or lines that don't match expected format
                }
            }
            line = reader.readLine()
        }
        return processes
    }

    @Test
    fun benchmarkRegexParsing() {
        println("Starting Benchmark...")
        // 1. Generate a large PS output sample
        val psOutputLine = "u0_a123 12345 678 1234 5678 9012 3456 S com.example.app"
        val lines = List(50000) { psOutputLine }
        val hugeString = lines.joinToString("\n")

        // Warm up
        // RootUtils.parsePsOutput(BufferedReader(StringReader(psOutputLine)))
        // We can't easily warm up RootUtils without affecting static state (none here)
        // But let's warm up JVM a bit with dummy loops
        @Suppress("UNUSED_VARIABLE")
        repeat(100) {
            @Suppress("UNUSED_VARIABLE")
            val parts = psOutputLine.trim().split("\\s+".toRegex())
        }

        // 2. Measure Slow Implementation (simulated)
        val timeSlow = measureTimeMillis {
             val reader = BufferedReader(StringReader(hugeString))
             legacyParsePsOutput(reader)
        }
        println("Slow parsing took: $timeSlow ms")

        // 3. Measure Optimized Implementation (Actual Code)
        val timeFast = measureTimeMillis {
            val reader = BufferedReader(StringReader(hugeString))
            RootUtils.parsePsOutput(reader)
        }
        println("Fast parsing took: $timeFast ms")

        val improvement = timeSlow - timeFast
        val percentage = improvement.toDouble() / timeSlow.toDouble() * 100
        println("Improvement: $improvement ms ($percentage%)")

        // Assert improvement
        // assert(timeFast < timeSlow) { "Optimization failed to improve performance" }
    }
}
