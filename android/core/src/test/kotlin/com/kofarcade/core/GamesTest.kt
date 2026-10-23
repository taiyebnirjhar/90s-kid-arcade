package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamesTest {
    @Test
    fun gamesAreFoundByRomName() {
        assertEquals("kof97", gameForFile("KOF97.zip")?.id)
        assertEquals("kof2005", gameForFile("kf2k5uni.zip")?.id)
        assertEquals("BC", gameForFile("kof2002.zip")?.power?.cmd)
        assertEquals("ABC", gameForFile("kof97.zip")?.power?.cmd)
        assertNull(gameForFile("random.zip"))
    }

    @Test
    fun shootersOnlyShowLabelledButtons() {
        assertEquals(listOf('A', 'B', 'C'), gameById("mslug")!!.faceButtons)
        assertEquals(listOf('A', 'B', 'C', 'D'), gameById("mslug5")!!.faceButtons)
    }

    @Test
    fun rostersLoadAndEveryMoveParses() {
        for (name in listOf("kof97", "kof2002")) {
            val roster = assertNotNull(loadRoster(name), name)
            assertTrue(roster.characters.size > 30, name)
            for (c in roster.characters) for (m in c.moves) {
                assertTrue(isValidCommand(m.cmd), "${c.name}: ${m.name} ${m.cmd}")
                if (!parseCommand(m.cmd).isSequence) assertNotNull(motionGlyph(m.cmd), "${c.name}: ${m.name} has no motion")
            }
        }
        for (g in GAMES) for (m in g.moves + g.system + listOfNotNull(g.power)) {
            assertTrue(isValidCommand(m.cmd), m.name)
        }
    }

    @Test
    fun everyFighterHasARoster() {
        for (g in GAMES.filter { it.layout == Layout.FIGHTER }) assertNotNull(loadRoster(g.roster!!), g.id)
    }

    @Test
    fun shortNames() {
        assertEquals("Orochinagi", Move("Ura 108 Shiki: Orochinagi", "2141236+A").shortName)
    }
}
