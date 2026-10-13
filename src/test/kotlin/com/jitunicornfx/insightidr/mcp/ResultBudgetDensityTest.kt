package com.jitunicornfx.insightidr.mcp

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A body under the character ceiling that is too dense to hold as a JSON tree.
 *
 * The character ceiling counts characters, and what a tree costs is set by how many values it holds.
 * Measured 2026-10-12: 78 bytes for each value of an array of one-character numbers, which is 39 for
 * each character of the body, where a Log Search page of the shape ContextLinkFoldTest builds takes 2
 * to 3.
 *
 * These belong in ResultBudgetTest, beside the tests they are modelled on. They are apart from it
 * only because that file had uncommitted work in another worktree when they were written.
 */
class ResultBudgetDensityTest {

    /** The line a cut notice carries for a body that held more than [ceiling] values. */
    private fun notParsed(ceiling: Int) =
        "\nThe response was not parsed: as JSON it holds more than $ceiling values, more than this server " +
            "reads into memory at once, so it was not checked for JSON.\n"

    /** 15,999,999 characters, one under the character ceiling: eight million values. */
    private fun eightMillionOnes(): String = "[" + "1,".repeat(7_999_998) + "1]"

    @Test
    fun `a body of eight million one-character values is rendered in the heap a test worker has`() {
        // Measured 2026-10-12: an OutOfMemoryError inside the parser in the 512 MB Gradle gives a test
        // worker, where the same shape was last parsed at 12,000,000 characters, and completed in 1 GB.
        // In a larger heap this proves nothing, and the test below is the one that counts.
        val raw = eightMillionOnes()
        assertTrue(raw.length <= ResultBudget.DEFAULT_PARSE_CEILING_CHARS, "under the character ceiling, which does not stop it")

        val rendered = try {
            renderWithinBudget(raw, ResultBudget(), ok = true)
        } catch (e: OutOfMemoryError) {
            fail("rendering a ${raw.length}-character body ran out of memory: ${e.message}")
        }

        assertEquals(raw.take(ResultBudget.DEFAULT_MAX_CHARS), rendered.text, "the start of the body, as it arrived")
        assertEquals(BudgetStrategy.HARD_CUT, rendered.strategy)
        assertTrue(notParsed(ResultBudget.DEFAULT_PARSE_CEILING_VALUES) in rendered.notice!!, rendered.notice)
    }

    @Test
    fun `rendering a body of eight million values does not build eight million values`() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "this JVM does not count what a thread allocates",
        )
        val raw = eightMillionOnes()
        val budget = ResultBudget()
        renderWithinBudget("[" + "1,".repeat(200_000) + "1]", budget, ok = true) // class loading is not what is measured

        val before = threads!!.currentThreadAllocatedBytes
        val rendered = try {
            renderWithinBudget(raw, budget, ok = true)
        } catch (e: OutOfMemoryError) {
            fail("rendering a ${raw.length}-character body ran out of memory: ${e.message}")
        }
        val allocated = threads.currentThreadAllocatedBytes - before

        assertTrue(rendered.text.length <= budget.maxChars)
        // Parsed, this body is a tree of some 624 MB, and rendering it allocated 2.6 GB when it was
        // measured in a heap large enough to finish: 162 bytes for each character of the body. Only
        // 200,000 of them can be shown.
        assertTrue(
            allocated < raw.length,
            "rendering ${raw.length} characters allocated $allocated bytes; only the first ${budget.maxChars} can be shown",
        )
    }

    @Test
    fun `a body cut for the values it holds says so, and is cut exactly as text is`() {
        // Valid JSON, 1,202 values of it, under a ceiling lowered to 1,000.
        val events = (0 until 400).joinToString(",\n") { """{"id":$it,"message":"m$it"}""" }
        val body = """{"events":[$events]}"""
        val budget = ResultBudget(maxChars = 4_000, parseCeilingValues = 1_000)
        // The same body sent down the text path by the other ceiling, which is the path this must take.
        val asText = renderWithinBudget(body, budget.copy(parseCeilingChars = 10, parseCeilingValues = Int.MAX_VALUE), ok = true)
        val tooLong = "\nThe response was too large to parse (over 10 characters), so it was not checked for JSON.\n"
        assertTrue(tooLong in asText.notice!!, asText.notice)

        val rendered = renderWithinBudget(body, budget, ok = true)

        assertEquals(asText.text, rendered.text)
        assertEquals(BudgetStrategy.LINE_CUT, rendered.strategy)
        // One line of the notice differs, and it is the one that says why.
        assertEquals(asText.notice!!.replace(tooLong, notParsed(1_000)), rendered.notice)
        assertFalse("The response was not JSON." in rendered.notice!!, "nobody read it to find out")
        assertFalse("no top-level array to trim" in rendered.notice!!, "it has one, and it was never looked for")
        assertFalse("too large to parse" in rendered.notice!!, "it is a thousandth of the length that would be")
    }

    @Test
    fun `a body is parsed at the value ceiling, and not at one value over it`() {
        fun array(values: Int) = "[" + (2..values).joinToString(",") { "1" } + "]"
        val budget = ResultBudget(maxChars = 2_000, parseCeilingValues = 5_000)

        val at = renderWithinBudget(array(5_000), budget, ok = true)
        assertEquals(BudgetStrategy.STRUCTURAL, at.strategy, "parsed, and trimmed as the document it is")
        assertTrue("of 4999 top-level entries" in at.notice!!, at.notice)

        val over = renderWithinBudget(array(5_001), budget, ok = true)
        assertEquals(BudgetStrategy.HARD_CUT, over.strategy)
        assertTrue(notParsed(5_000) in over.notice!!, over.notice)
    }

    @Test
    fun `a download of one JSON object to a line is still said not to be JSON`() {
        // 12,000 values in it, and it is not a document: the parser reads the first line and refuses the
        // rest. So the first line is all that is counted, and the notice says what was found by parsing
        // rather than what a count of the whole text would have feared.
        val body = ("""{"a":1,"b":[2,3],"c":"x"}""" + "\n").repeat(2_000)
        val budget = ResultBudget(maxChars = 4_000, parseCeilingValues = 1_000)

        val rendered = renderWithinBudget(body, budget, ok = true)

        assertEquals(BudgetStrategy.LINE_CUT, rendered.strategy)
        assertTrue("\nThe response was not JSON.\n" in rendered.notice!!, rendered.notice)
    }

    @Test
    fun `a body over the value ceiling that fits the budget is returned as it arrived`() {
        // Not reachable with the ceilings as shipped, where a body over one is five times the budget at
        // least. Under a lowered one it is the text path's ordinary answer: whole, and not re-rendered.
        val body = "[" + (1..600).joinToString(" , ") { "$it" } + "]"
        val rendered = renderWithinBudget(body, ResultBudget(parseCeilingValues = 500), ok = true)

        assertEquals(body, rendered.text)
        assertNull(rendered.notice)
    }
}
