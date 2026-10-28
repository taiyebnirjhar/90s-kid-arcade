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

    /**
     * The seat rule (docs/adding-a-game.md): in a room, a seat goes to a
     * watcher only when the game itself shows its player out, so every game
     * must say where it keeps each player's status.
     */
    @Test
    fun everyGameSaysWhosInEachSeat() {
        for (game in GAMES) {
            val seats = assertNotNull(game.seats, "${game.id}: no seat status (Game.seats); see docs/adding-a-game.md")
            assertEquals(game.players, seats.offsets.size, "${game.id}: one status byte per player")
            assertEquals(seats.offsets.size, seats.offsets.toSet().size, "${game.id}: a byte per player, all different")
        }
    }

    @Test
    fun aSeatWithNoCountdownByteFreesOnlyAfterStayingOutForTheCountdown() {
        val watch = SeatWatch(SeatStatus(listOf(1, 2), setOf(0), outForMs = 18_000))
        assertEquals(false, watch.sample(0, 1, 0))
        assertEquals(false, watch.sample(0, 0, 1_000), "the countdown just started")
        assertEquals(false, watch.sample(0, 0, 18_999))
        assertEquals(false, watch.sample(0, 1, 19_000), "the player continued")
        assertEquals(false, watch.sample(0, 0, 20_000), "the count starts over")
        assertEquals(false, watch.sample(0, 0, 37_999))
        assertEquals(true, watch.sample(0, 0, 38_000))
        assertEquals(false, watch.sample(1, 1, 38_000), "each seat on its own")
        assertEquals(false, watch.sample(5, 0, 38_000), "no such seat")
    }

    @Test
    fun aSeatWithACountdownByteFreesAtOnce() {
        val watch = SeatWatch(SeatStatus(listOf(1, 2), setOf(0, 3)))
        assertEquals(false, watch.sample(0, 2, 0))
        assertEquals(true, watch.sample(0, 3, 0))
    }

    @Test
    fun shootersOnlyShowLabelledButtons() {
        assertEquals(listOf('A', 'B', 'C'), gameById("mslug")!!.faceButtons)
        assertEquals(listOf('A', 'B', 'C', 'D'), gameById("mslug5")!!.faceButtons)
    }

    @Test
    fun rostersLoadAndEveryMoveParses() {
        for (name in GAMES.mapNotNull { it.roster }.distinct()) {
            val roster = assertNotNull(loadRoster(name), name)
            assertTrue(roster.characters.size >= 10, name)
            for (c in roster.characters) for (m in c.moves) {
                assertTrue(isValidCommand(m.cmd), "${c.name}: ${m.name} ${m.cmd}")
                // Buttons alone (Double Dragon's ABCD) have no motion to draw.
                val c1 = parseCommand(m.cmd)
                if (!c1.isSequence && c1.parts.any { it.dir != 5 }) assertNotNull(motionGlyph(m.cmd), "${c.name}: ${m.name} has no motion")
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

    // Where a game's picture comes from when the app is built.
    private fun artUrl(game: Game, kind: Art, set: String? = null) = artFile(game, kind, set)?.let(::artSource)

    /**
     * app/art.txt (what the build packs into the app) lists every picture
     * the app can show. KOF_WRITE_ART_LIST=1 rewrites it after a game's added.
     */
    @Test
    fun theAppShipsEveryPicture() {
        val list = java.io.File("../app/art.txt")
        val all = allArt()
        assertTrue(all.size >= GAMES.size * 2)
        if (System.getenv("KOF_WRITE_ART_LIST") == "1") list.writeText(all.joinToString("\n", postfix = "\n"))
        assertEquals(all, list.readLines().filter { it.isNotBlank() }, "app/art.txt is out of date: run with KOF_WRITE_ART_LIST=1")
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
