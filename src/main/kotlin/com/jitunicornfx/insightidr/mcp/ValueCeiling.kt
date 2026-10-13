package com.jitunicornfx.insightidr.mcp

import kotlinx.serialization.json.JsonElement

/**
 * Most values a response body may hold and still be read into a JSON tree.
 *
 * What a tree costs is set by how many values it holds, and a limit on the length of the text does
 * not limit that. Measured 2026-10-12 with kotlinx.serialization 1.11.0, a tree keeps 78 bytes for
 * each value of an array of one-character numbers, 85 for one of empty objects and 154 to 184 for
 * one of small objects - where a Log Search page of the shape ContextLinkFoldTest builds keeps 2 to
 * 3 for each CHARACTER, and holds 100,000 to 280,000 values in 16,000,000 of them. `[1,1,1,...]` of
 * that length is eight million values, at that rate a tree of 624 MB. In a 512 MB heap it was an
 * OutOfMemoryError inside the parser, and two other shapes were parsed and then ran the encoder out
 * instead, so it is the tree that has to be bounded and not the call that builds it. An
 * OutOfMemoryError is an Error, which `apiTool` does not catch, and the heap is every session's.
 *
 * At this many values the largest tree measured was 175 MB. Ten bodies at the ceiling, of the shapes
 * that cost most and four of them 16,000,000 characters long as well, were each parsed and rendered
 * in a 384 MB heap. In 256 MB three of those four ran out, in the encoder.
 *
 * Nothing sets this server's heap, which is whatever the JVM takes of the machine it is started on.
 * No fixture in this project is a recorded response of any size, so that no InsightIDR endpoint
 * answers with a million values is not known; a body that does is not parsed, and is said not to
 * have been.
 */
internal const val MAX_PARSED_VALUES = 1_000_000

/**
 * Whether the parser would build more than [max] values from [text] - counted, without building any.
 *
 * A value is an object, an array, a string, a number or a literal, at any depth. This counts the
 * places the parser starts one, outside quoted strings, and those are the places kotlinx.serialization
 * 1.11.0 starts one, which are not only the places JSON has. Read from its `JsonTreeReader`: the start
 * of the text; whatever follows a `[`, a `{` or a `,` and is not itself a `}`, `]`, `:` or `,`; and
 * whatever follows a `]` on the same terms, because its array reader does not stop at a `]`. Checked
 * 2026-10-12: `[1]2]3]` parses, as the array 1, 2, 3. A count of commas and opening brackets, which is
 * what JSON would need, called that text two values long, and it can be made eight million.
 *
 * For a JSON document the count is exact. For anything else it is the most the parser can have built
 * by the time it gives up, because it reads a quoted string, and nothing else, the way this does: to
 * the next quote that no backslash precedes.
 *
 * The count stops where the first array or object ends. The parser builds nothing after that, so a
 * download of one JSON object to a line is counted as its first line, which is all of it that is
 * ever parsed before it is found not to be a document. A text that opens neither is one value.
 *
 * Allocates nothing, and reads no further than the value that settles it.
 */
internal fun holdsMoreValuesThan(text: String, max: Int = MAX_PARSED_VALUES): Boolean {
    // Every value after the first takes at least a character of its own.
    if (text.length <= max) return false
    var i = 0
    while (i < text.length && text[i].isJsonSpace()) i++
    if (i == text.length || (text[i] != '[' && text[i] != '{')) return false

    var values = 1
    var depth = 0
    // What was read last lets a value begin here.
    var mayBegin = false
    // What was read last was a `]`. Whether it ended its array is settled by what is read next.
    var closing = false
    while (i < text.length) {
        val c = text[i++]
        if (c.isJsonSpace()) continue
        val begins = c != '}' && c != ']' && c != ':' && c != ','
        if (closing) {
            closing = false
            if (!begins && --depth == 0) return false
        }
        if (mayBegin && begins && ++values > max) return true
        mayBegin = false
        when (c) {
            '[', '{' -> {
                depth++
                mayBegin = true
            }
            ',' -> mayBegin = true
            ']' -> {
                closing = true
                mayBegin = true
            }
            '}' -> if (--depth == 0) return false
            '"' -> i = afterString(text, i)
        }
    }
    return false
}

/** The four characters the parser skips between tokens. */
private fun Char.isJsonSpace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'

/** The index after the quote that closes the string whose first character is at [from]. */
private fun afterString(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        val c = text[i++]
        if (c == '"') return i
        if (c == '\\') i++
    }
    return i
}

/**
 * [body] as a JSON tree - or null, and nothing read, when it holds more than [MAX_PARSED_VALUES]
 * values. Throws whatever the parser throws for a body that is not JSON.
 *
 * For a caller that reads a response to act on it: whether a query is still running, where its next
 * page is, which events to write to a file. Until 2026-10-12 each of those parsed whatever the
 * client handed over, which is up to 64 MiB: looking for a link in 60,000,000 characters of
 * `[1,1,1,...]` was an OutOfMemoryError in a 2 GB heap. The response budget makes the same check for
 * itself, because its notice has to say which of the two it was.
 */
internal fun parseWithinCeiling(body: String): JsonElement? =
    if (holdsMoreValuesThan(body)) null else JsonCodec.compact.parseToJsonElement(body)
