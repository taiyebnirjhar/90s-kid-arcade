package com.kofarcade.core.net

/**
 * Room chat's word filter (on unless the player turns it off): swear words
 * and slurs become stars, on the phone that shows them. Words are matched
 * whole, after undoing the usual disguises (sh1t, f.u.c.k, fuuuck), so
 * "class" and "Scunthorpe" stay as they are.
 */
object ChatFilter {
    // Roots: a word starting with one of these is filtered (fuck, fucking, fucker).
    private val roots = listOf(
        "fuck", "fuk", "fck", "shit", "bitch", "cunt", "dick", "cock", "pussy", "asshole", "bastard",
        "motherfuck", "whore", "slut", "wank", "twat", "prick", "nigg", "fag", "fagg", "retard", "chink",
        "kike", "spic", "tranny", "rape", "rapist", "porn", "dildo", "jizz", "cum",
        // Bangla, as typed in Latin letters (the owner's players).
        "khanki", "magi", "chudi", "choda", "bokachoda", "baal", "bal", "shala", "haramjada", "haramzada",
    )

    // Whole words only: roots that start harmless words (cumin, ball, shall, magic, spicy, cockpit, dickens).
    private val wholeOnly = setOf("cum", "bal", "baal", "magi", "shala", "spic", "fag", "rape", "dick", "cock", "prick")

    private val leet = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')

    /** [text] with every filtered word starred out. */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text[i])) {
                out.append(text[i++])
                continue
            }
            // A word: letters, digits and the disguises (f.u.c.k counts as one word).
            var j = i
            while (j < text.length && (isWordChar(text[j]) || (text[j] in ".-_*" && j + 1 < text.length && isWordChar(text[j + 1])))) j++
            val word = text.substring(i, j)
            out.append(if (bad(word)) "*".repeat(word.count { isWordChar(it) }) else word)
            i = j
        }
        return out.toString()
    }

    /** Whether [word] is one the filter stars out. */
    fun bad(word: String): Boolean {
        val plain = word.lowercase().mapNotNull { c ->
            when {
                c in leet -> leet[c]
                c.isLetter() -> c
                else -> null
            }
        }.joinToString("")
        if (plain.isEmpty()) return false
        // Letters said more than twice in a row count once (fuuuck).
        val squeezed = plain.replace(Regex("(.)\\1{2,}"), "$1")
        return listOf(plain, squeezed).any { w ->
            roots.any { r -> if (r in wholeOnly) w == r || w == r + "s" else w.startsWith(r) }
        }
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c in leet
}
