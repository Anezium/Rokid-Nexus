package com.anezium.rokidbus.plugin.agents

/** Merge a delta fragment that arrived without its item's initial snapshot. */
internal fun mergeLitterFragment(snapshot: String, fragment: String): String {
    if (fragment.isEmpty()) return snapshot
    val prefix = IntArray(fragment.length)
    for (index in 1 until fragment.length) {
        var matched = prefix[index - 1]
        while (matched > 0 && fragment[index] != fragment[matched]) matched = prefix[matched - 1]
        if (fragment[index] == fragment[matched]) matched++
        prefix[index] = matched
    }
    var overlap = 0
    snapshot.forEach { char ->
        if (overlap == fragment.length) overlap = prefix[overlap - 1]
        while (overlap > 0 && fragment[overlap] != char) overlap = prefix[overlap - 1]
        if (fragment[overlap] == char) overlap++
    }
    return (snapshot + fragment.substring(overlap)).takeLast(LitterProtocol.MAX_TEXT)
}
