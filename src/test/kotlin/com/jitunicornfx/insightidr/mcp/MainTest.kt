package com.jitunicornfx.insightidr.mcp

import kotlin.test.Test

class MainTest {

    /**
     * Only the `--help` path is safely unit-testable: it prints usage to stderr and returns.
     * The other branches either call `exitProcess` (config error / unknown option) or block on a
     * transport (`--stdio` / `--http`), which would kill or hang the test JVM.
     */
    @Test
    fun `help flags print usage and return without exiting`() {
        main(arrayOf("--help"))
        main(arrayOf("-h"))
    }
}
