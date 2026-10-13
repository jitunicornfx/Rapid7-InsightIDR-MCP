package com.jitunicornfx.insightidr.mcp

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a JSON tree lost to keys that an object repeats: how many members ([dropped]), and the keys
 * they were under, each once and in the order the response first has them.
 *
 * RFC 8259 says the names in an object SHOULD be unique, not that they are, and kotlinx.serialization
 * 1.11.0 keeps the last member under each name without saying it met another. Observed 2026-10-12
 * through [renderWithinBudget]: `{"a":1,"a":2}` came back as `{"a": 2}`, with no notice. Its
 * `JsonConfiguration` has no setting that refuses or reports a repeated key, read the same day from
 * the fields of the class, so they are found by counting.
 *
 * No fixture in this project repeats a key, which does not show that the API never does.
 */
internal class RepeatedKeys(val dropped: Long, val keys: List<String>)

/**
 * What [root], the tree parsed from [text], lost to repeated keys - or null when it lost nothing.
 *
 * A key is a quoted string with a colon after it, and a text that parsed has a colon nowhere else
 * outside a string. Each key becomes a member of the tree unless a later member of the same object
 * takes its place, so the members that are gone are the keys in the text less the members in the
 * tree. That much is counted first, allocating nothing, and is all that is done for a body that
 * repeats no key.
 *
 * One that does is read a second time for which keys they were. A key is repeated when the text has
 * it more often than the tree has objects with it, and by that many members.
 */
internal fun repeatedKeys(text: String, root: JsonElement): RepeatedKeys? {
    var keys = 0L
    forEachKey(text) { _, _ -> keys++ }
    var members = 0L
    forEachObject(root) { members += it.size }
    if (keys <= members) return null

    // For each key, how often the text has it less how many objects of the tree do.
    val surplus = LinkedHashMap<String, Int>()
    forEachKey(text) { from, to -> keyAt(text, from, to)?.let { surplus.merge(it, 1, Int::plus) } }
    forEachObject(root) { for (key in it.keys) surplus.computeIfPresent(key) { _, count -> count - 1 } }
    val repeated = surplus.filterValues { it > 0 }
    if (repeated.isEmpty()) return null
    return RepeatedKeys(repeated.values.sumOf { it.toLong() }, repeated.keys.toList())
}

/**
 * Calls [key] for every key in [text], with where its characters start and where its closing quote
 * is. A string is read as [holdsMoreValuesThan] reads one, which is as the parser does.
 */
private inline fun forEachKey(text: String, key: (from: Int, to: Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        if (text[i++] != '"') continue
        val from = i
        i = afterString(text, i)
        var next = i
        while (next < text.length && text[next].isJsonSpace()) next++
        if (next < text.length && text[next] == ':') key(from, i - 1)
    }
}

/** The key written from [from] up to [to] in [text], as the parser reads it: with its escapes resolved. */
private fun keyAt(text: String, from: Int, to: Int): String? {
    if ((from until to).none { text[it] == '\\' }) return text.substring(from, to)
    return try {
        JsonCodec.compact.decodeFromString(String.serializer(), text.substring(from - 1, to + 1))
    } catch (_: Exception) {
        null
    }
}

/** Calls [visit] for every object in [root], at any depth. With a stack of its own, for the reason [isJson] has one. */
private inline fun forEachObject(root: JsonElement, visit: (JsonObject) -> Unit) {
    val open = ArrayDeque<Iterator<JsonElement>>()
    open.addLast(listOf(root).iterator())
    while (open.isNotEmpty()) {
        val values = open.last()
        if (!values.hasNext()) {
            open.removeLast()
            continue
        }
        when (val value = values.next()) {
            is JsonObject -> {
                visit(value)
                open.addLast(value.values.iterator())
            }
            is JsonArray -> open.addLast(value.iterator())
            is JsonPrimitive -> Unit
        }
    }
}
