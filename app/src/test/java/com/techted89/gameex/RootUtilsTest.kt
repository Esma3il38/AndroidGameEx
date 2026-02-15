package com.techted89.gameex

import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class RootUtilsTest {

    @Test
    fun testParsePsOutput_Robustness() {
        val input = """
            USER      PID   PPID  VSIZE  RSS   WCHAN            PC  NAME
            u0_a139   6575  791   1445700 89280 SyS_epoll_ 0000000000 S com.android.chrome
            root      1     0     1000   100   c0000000 0000000000 S init
            u0_a140   6576  791   1445700 89280 SyS_epoll_ S com.android.browser
        """.trimIndent()

        val reader = BufferedReader(StringReader(input))
        val processes = RootUtils.parsePsOutput(reader)

        // 1st line: Header (pid parsing fails) -> Skipped
        // 2nd line: 9 columns -> PID 6575
        // 3rd line: 9 columns -> PID 1
        // 4th line: 8 columns -> PID 6576

        assert(processes.size == 3) { "Expected 3 processes, found ${processes.size}" }

        assert(processes[0].pid == 6575)
        assert(processes[0].name == "com.android.chrome")

        assert(processes[1].pid == 1)
        assert(processes[1].name == "init")

        assert(processes[2].pid == 6576)
        assert(processes[2].name == "com.android.browser")
    }
}
