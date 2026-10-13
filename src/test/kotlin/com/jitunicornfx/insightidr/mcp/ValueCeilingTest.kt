package com.jitunicornfx.insightidr.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValueCeilingTest {

    /** How many values the parser built from [text], or null when it refused it. */
    private fun built(text: String): Int? {
        val root = try {
            JsonCodec.compact.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return null
        }
        var values = 0
        val open = ArrayDeque<Iterator<JsonElement>>()
        open.addLast(listOf(root).iterator())
        while (open.isNotEmpty()) {
            val next = open.last()
            if (!next.hasNext()) {
                open.removeLast()
                continue
            }
            values++
            when (val value = next.next()) {
                is JsonObject -> open.addLast(value.values.iterator())
                is JsonArray -> open.addLast(value.iterator())
                is JsonPrimitive -> Unit
            }
        }
        return values
    }

    /** What [holdsMoreValuesThan] counted in [text]: the least ceiling it is not over. */
    private fun counted(text: String): Int = (1..text.length + 1).first { !holdsMoreValuesThan(text, it) }

    // Written apart, so that no tool between here and the disk can take the two for an escape.
    private val unicode = "\\" + "u"

    @Test
    fun `the count is what the parser builds from a JSON document`() {
        val documents = listOf(
            "[]" to 1,
            "{}" to 1,
            "[1]" to 2,
            "[1,2,3]" to 4,
            """{"a":1}""" to 2,
            """{"a":[1,2],"b":{}}""" to 5,
            "[[],[[]],{}]" to 5,
            " [ 1 ,\n\t\"two\" ,\r\n { \"three\" : [ 3 ] } ] " to 6,
            "[true,false,null,-1.5e3]" to 5,
            // Nothing inside a string is structure.
            """["a,b","[","{","]","}",",,,,"]""" to 7,
            """{"k,[{":"v]}","[":[","]}""" to 4,
            // A quote a backslash precedes does not end the string, and one that two precede does.
            """["he said \"no, never\"",1]""" to 3,
            """["back\\",1,"\\\\",2,"\\\",3"]""" to 6,
            """["${unicode}005b${unicode}002c${unicode}0022",1]""" to 3,
            // Read as values by this parser, though JSON has no such thing.
            "[yes,no,0010]" to 4,
            // Read as ONE array by this parser, which does not stop at a `]` that a value follows.
            "[1]2]3]" to 4,
            "[[]]1]" to 3,
            """{"a":[1]"two"]{"three":3}]}""" to 6,
            "[1] [2] ]" to 4,
        )
        for ((text, values) in documents) {
            assertEquals(values, built(text), "what the parser built from $text")
            assertEquals(values, counted(text), "what was counted in $text")
        }
    }

    /** JSON documents, and what a stray edit makes of them. Seeded: the same ones every run. */
    private class Documents(seed: Int) {
        private val random = Random(seed)
        private val spaces = listOf("", "", "", " ", "\n", "\t", "\r\n", "  ")
        private val pieces = listOf("a", "b", ",", "[", "]", "{", "}", ":", " ", "\\\"", "\\\\", "\\n", "x y", "é", "1")
        private val edits = ",[]{}\":\\ 1a"

        private fun space() = spaces.random(random)

        private fun string(prefix: String = "") =
            "\"" + prefix + (0 until random.nextInt(4)).joinToString("") { pieces.random(random) } + "\""

        private fun scalar() = when (random.nextInt(6)) {
            0 -> string()
            1 -> random.nextInt(-50, 5_000).toString()
            2 -> "true"
            3 -> "null"
            4 -> "-1.5e3"
            else -> "word"
        }

        private fun array(depth: Int) =
            (0 until random.nextInt(4)).joinToString(space() + "," + space(), "[" + space(), space() + "]") { value(depth + 1) }

        // Keys differ, so the tree holds every member the parser read.
        private fun obj(depth: Int) =
            (0 until random.nextInt(4)).joinToString(space() + "," + space(), "{" + space(), space() + "}") {
                string(prefix = "k$it") + space() + ":" + space() + value(depth + 1)
            }

        private fun value(depth: Int): String = when (if (depth >= 5) 2 else random.nextInt(3)) {
            0 -> array(depth)
            1 -> obj(depth)
            else -> scalar()
        }

        fun document(): String = space() + (if (random.nextBoolean()) array(0) else obj(0)) + space()

        fun edited(text: String): String {
            val at = random.nextInt(text.length)
            return when (random.nextInt(3)) {
                0 -> text.removeRange(at, at + 1)
                1 -> text.substring(0, at) + edits.random(random) + text.substring(at)
                else -> text.substring(0, at) + edits.random(random) + text.substring(at + 1)
            }
        }
    }

    @Test
    fun `the count is exact for any document, and never under what the parser builds from anything`() {
        val documents = Documents(seed = 20261012)
        var parsedEdits = 0
        var refusedEdits = 0
        repeat(4_000) {
            val text = documents.document()
            assertEquals(assertNotNull(built(text), text), counted(text), text)

            repeat(6) {
                val edited = documents.edited(text)
                val values = built(edited)
                if (values == null) {
                    refusedEdits++
                } else {
                    parsedEdits++
                    // An edit can repeat a key, and the tree then holds less than the parser built.
                    assertTrue(values <= counted(edited), "$values values were built from $edited")
                }
            }
        }
        // Both kinds were met, so the comparison above was made on documents nobody wrote by hand.
        assertTrue(parsedEdits > 1_000 && refusedEdits > 1_000, "$parsedEdits edits parsed and $refusedEdits did not")
    }

    @Test
    fun `the count stops where the document does`() {
        // One object to a line, as a log download can be. The parser reads the first and refuses the
        // rest, so that is as far as there is anything to count.
        val lines = """{"a":1,"b":[2,3]}""" + "\n"
        assertEquals(5, counted(lines.repeat(1_000)))
        assertFalse(holdsMoreValuesThan(lines.repeat(400_000), 1_000_000), "1,600,000 values, and never one document")

        assertEquals(3, counted("[1,2] , , , , , , , ,"))
        assertEquals(1, counted("[],[],[],[],[]"))
    }

    @Test
    fun `a text that opens neither an array nor an object is one value`() {
        val byteOrderMark = 0xFEFF.toChar()
        for (text in listOf("hello, world, " + ",".repeat(50), "\"a,b,c,[,{\"", "12345", ", , , , ,", "", "   ", "$byteOrderMark[1,2,3]")) {
            assertFalse(holdsMoreValuesThan(text, 1), text)
            assertTrue((built(text) ?: 0) <= 1, text)
        }
    }

    @Test
    fun `a document is refused at one value more than the ceiling, whatever holds the values`() {
        fun array(values: Int) = "[" + "1,".repeat(values - 2) + "1]"
        assertEquals(1_000_000, built(array(1_000_000)))
        assertFalse(holdsMoreValuesThan(array(1_000_000)))
        assertTrue(holdsMoreValuesThan(array(1_000_001)))

        fun objects(values: Int) = """{"events":[""" + """{"a":1,"b":2},""".repeat((values - 5) / 3) + """{"a":1,"b":2}]}"""
        assertEquals(1_000_001, built(objects(1_000_001)))
        assertFalse(holdsMoreValuesThan(objects(1_000_000 - 2)))
        assertTrue(holdsMoreValuesThan(objects(1_000_001)))

        // Empty ones are counted as the one value each is, not as room for a value.
        val empties = "[" + "{},".repeat(999_998) + "[]]"
        assertEquals(1_000_000, built(empties))
        assertFalse(holdsMoreValuesThan(empties))

        // No comma in it, and one opening bracket: the array this parser reads past every `]` of.
        fun unclosed(values: Int) = "[" + "1]".repeat(values - 1)
        assertEquals(1_000_000, built(unclosed(1_000_000)))
        assertFalse(holdsMoreValuesThan(unclosed(1_000_000)))
        assertTrue(holdsMoreValuesThan(unclosed(1_000_001)))
    }

    @Test
    fun `counting a large body allocates nothing`() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "this JVM does not count what a thread allocates",
        )
        // 16,000,000 characters and under the ceiling, so all of it is read: the case that costs most.
        val event = """{"id":1234,"message":"${"x".repeat(200)} \"quoted, [bracketed]\" {braced}"}"""
        val raw = """{"events":[""" + "$event,".repeat(16_000_000 / (event.length + 1) - 1) + event + "]}"
        assertFalse(holdsMoreValuesThan(raw.take(400_000), 100_000)) // class loading is not what is measured

        val before = threads!!.currentThreadAllocatedBytes
        val over = holdsMoreValuesThan(raw)
        val allocated = threads.currentThreadAllocatedBytes - before

        assertFalse(over)
        assertTrue(allocated < 64 * 1024, "counting ${raw.length} characters allocated $allocated bytes")
    }

    @Test
    fun `a body over the ceiling is not read, and one that is not JSON still says so`() {
        assertEquals(JsonCodec.compact.parseToJsonElement("""{"a":[1,2]}"""), parseWithinCeiling("""{"a":[1,2]}"""))
        assertNull(parseWithinCeiling("[" + "1,".repeat(1_000_000) + "1]"))
        assertFailsWith<SerializationException> { parseWithinCeiling("this is not json") }
    }
}
