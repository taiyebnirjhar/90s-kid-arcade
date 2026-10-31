package com.kofarcade.core.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatFilterTest {
    @Test
    fun swearWordsAreStarredHarmlessWordsAreNot() {
        assertEquals("GG ****", ChatFilter.clean("GG shit"))
        assertEquals("what the ****", ChatFilter.clean("what the fuck"))
        assertEquals("*******!", ChatFilter.clean("fucking!"))
        assertEquals("****", ChatFilter.clean("sh1t"))
        assertEquals("****", ChatFilter.clean("f.u.c.k"))
        assertEquals("******", ChatFilter.clean("fuuuck"))
        for (fine in listOf("class", "Scunthorpe", "cumin", "ball", "shall", "magic", "spicy", "cockpit", "grapes", "assess", "Dickens", "GG wp", "nice combo")) {
            assertEquals(fine, ChatFilter.clean(fine), fine)
        }
    }
}
