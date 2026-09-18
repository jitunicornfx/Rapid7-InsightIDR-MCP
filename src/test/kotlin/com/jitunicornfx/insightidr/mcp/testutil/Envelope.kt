package com.jitunicornfx.insightidr.mcp.testutil

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The parts of a tool result that carries one untrusted-data envelope. */
data class Envelope(
    /** The one-time id both markers carry. */
    val nonce: String,
    /** Server-authored text ahead of the BEGIN marker: status line, summary, and the preamble. */
    val before: String,
    /** The untrusted data, exactly as fenced. */
    val body: String,
    /** Server-authored text after the END marker: the budget notice, when there is one. */
    val after: String,
    val beginIndex: Int,
    val endIndex: Int,
)

private val BEGIN = Regex("""----- BEGIN UNTRUSTED INSIGHTIDR API DATA \[id:([0-9a-f]{16})] -----""")
private val END = Regex("""----- END UNTRUSTED INSIGHTIDR API DATA \[id:([0-9a-f]{16})] -----""")

/**
 * Split [text] around its envelope, asserting the properties every envelope must have: exactly one
 * BEGIN and one END, in that order, carrying the same id, which the preamble announces.
 *
 * Tests go through this rather than matching marker strings themselves, so the envelope's format
 * is pinned in one place and "exactly one marker" is checked everywhere for free.
 */
fun parseEnvelope(text: String): Envelope {
    val begins = BEGIN.findAll(text).toList()
    val ends = END.findAll(text).toList()
    assertEquals(1, begins.size, "exactly one BEGIN marker")
    assertEquals(1, ends.size, "exactly one END marker")
    val begin = begins.single()
    val end = ends.single()
    assertTrue(begin.range.last < end.range.first, "BEGIN must come before END")
    val nonce = begin.groupValues[1]
    assertEquals(nonce, end.groupValues[1], "both markers must carry the same id")

    val before = text.substring(0, begin.range.first)
    assertTrue("[id:$nonce]" in before, "the preamble must tell the reader which id to trust")
    val body = text.substring(begin.range.last + 1, end.range.first).removePrefix("\n").removeSuffix("\n")
    assertTrue(nonce !in body, "the id must never appear inside the data it fences")
    return Envelope(nonce, before, body, text.substring(end.range.last + 1), begin.range.first, end.range.first)
}
