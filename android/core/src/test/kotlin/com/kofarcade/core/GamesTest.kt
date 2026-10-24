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

    @Test
    fun artUrlsMatchLibretroThumbnailNames() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/FBNeo_-_Arcade_Games/master/Named_Titles/" +
                "The%20King%20of%20Fighters%20%2797%20%28NGM-2320%29.png",
            artUrl(gameById("kof97")!!, Art.TITLE),
        )
        // "/" in a name becomes "_".
        assertTrue(artUrl(gameById("mslug2")!!, Art.SNAP)!!.contains("Super%20Vehicle-001_II"))
        assertTrue(artUrl(gameById("sengoku3")!!, Art.TITLE)!!.contains("Sengoku%203%20_%20Sengoku"))
        for (g in GAMES) assertNotNull(g.art, g.id)
    }

    @Test
    fun kof2005ShowsTheVersionYouHave() {
        val g = gameById("kof2005")!!
        assertTrue("2005%20Unique" in artUrl(g, Art.TITLE)!!)
        assertTrue("Extra%20Plus" in artUrl(g, Art.TITLE, "kf10thep")!!)
        // Versions outside the game's list keep the game's own picture.
        assertEquals(artUrl(g, Art.TITLE), artUrl(g, Art.TITLE, "kof10thd"))
    }
}
