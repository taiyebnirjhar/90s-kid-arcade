package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacroTest {
    private fun dirs(frames: List<Frame>) = frames.map { it.dir }

    @Test
    fun parsesDirectionsChargeAndButtons() {
        assertEquals(
            Command(listOf(2, 3, 6, 3, 2, 1, 4).map { Part(it, false) }, "A"),
            parseCommand("2363214+A"),
        )
        assertEquals(Command(listOf(Part(4, true), Part(6, false)), "AC"), parseCommand("[4]6 + ca"))
        assertEquals("A", parseCommand("236A").buttons)
        assertTrue(parseCommand("BC").parts.isEmpty())
    }

    @Test
    fun rejectsNonsense() {
        assertFailsWith<CommandException> { parseCommand("") }
        assertFailsWith<CommandException> { parseCommand("236+X") }
        assertFailsWith<CommandException> { parseCommand("[46]A") }
        assertFailsWith<CommandException> { parseCommand("0") }
    }

    @Test
    fun holdsEachDirectionThenButtonsThenReleases() {
        val f = compile("236+A", timing = Timing(step = 2, press = 3))
        assertEquals(listOf(2, 2, 3, 3, 6, 6, 6, 6, 6, 5), dirs(f))
        assertEquals(listOf("", "", "", "", "", "", "A", "A", "A", ""), f.map { it.buttons })
    }

    @Test
    fun mirrorsWhenFacingLeft() {
        val f = compile("2363214+A", facingLeft = true, timing = Timing(step = 1, press = 1))
        assertEquals(listOf(2, 1, 4, 1, 2, 3, 6, 6, 5), dirs(f))
    }

    @Test
    fun chargeHoldsForTheChargeTime() {
        val f = compile("[2]8+A", timing = Timing(step = 2, charge = 10, press = 1))
        assertEquals(10, f.count { it.dir == 2 })
    }

    @Test
    fun buttonOnlyCommandsPressTogether() {
        assertEquals(listOf(Frame(5, "CD"), Frame(5, "CD"), Frame(5, "")), compile("CD", timing = Timing(press = 2)))
    }

    @Test
    fun playerAdvancesOneFramePerTickAndNeverSkips() {
        val p = MacroPlayer(compile("236+A", timing = Timing(step = 1, press = 1)))
        assertEquals(2, p.tick(10)?.dir)
        assertEquals(2, p.tick(10)?.dir) // same frame: no advance
        assertEquals(3, p.tick(15)?.dir) // clock jumped: still one step
        assertEquals(6, p.tick(16)?.dir)
        assertEquals("A", p.tick(17)?.buttons)
        assertEquals(5, p.tick(18)?.dir)
        assertNull(p.tick(19))
        assertTrue(p.done)
    }

    @Test
    fun sequencesPressEachInputSeparately() {
        val cmd = parseCommand("D, B, C, C, 8")
        assertTrue(cmd.isSequence)
        assertEquals(listOf("D", "B", "C", "C", ""), cmd.steps.map { it.buttons })
        val f = compile("C,C", timing = Timing(press = 2, gap = 1))
        // Released between the two presses, so the game sees C twice.
        assertEquals(listOf("C", "C", "", "C", "C", ""), f.map { it.buttons })
        // Sequences mirror too: forward becomes back.
        assertEquals(4, compile("6,B", facingLeft = true, timing = Timing(step = 1))[0].dir)
        assertFailsWith<CommandException> { parseCommand("A,,B") }
    }

    @Test
    fun arrowLabels() {
        assertEquals("↓↙←↙↓↘→ A", toArrows("2141236+A"))
        assertEquals("[←]→ B", toArrows("[4]6+B"))
        assertEquals("D B C C ↑", toArrows("D,B,C,C,8"))
        assertEquals("→ D C B →", toArrows("6,D,C,B,6"))
    }

    @Test
    fun stickAngleMapsToNumpad() {
        assertEquals(5, angleToDir(0f, 0f, 14f))
        assertEquals(6, angleToDir(40f, 0f, 14f))
        assertEquals(4, angleToDir(-40f, 0f, 14f))
        assertEquals(8, angleToDir(0f, -40f, 14f))
        assertEquals(2, angleToDir(0f, 40f, 14f))
        assertEquals(3, angleToDir(30f, 30f, 14f))
        assertEquals(1, angleToDir(-30f, 30f, 14f))
        assertEquals(7, angleToDir(-30f, -30f, 14f))
        assertEquals(9, angleToDir(30f, -30f, 14f))
    }

    @Test
    fun arcPositionsStepCounterClockwise() {
        assertEquals(listOf(Pt(0f, -100f), Pt(-100f, 0f), Pt(0f, 100f)), arcPositions(3, 100f, 90f, 90f))
    }

    @Test
    fun motionIconsTraceTheStickPath() {
        val qcf = motionPoints("236+A")
        assertEquals(Pt(24f, 41f), qcf.first())
        assertEquals(Pt(41f, 24f), qcf.last())
        val end = motionPoints("236236+A").last()
        assertTrue(end.x < 41f && end.y == 24f)
        assertEquals(2, motionPoints("[4]6+A").size)
        assertNull(motionGlyph("CD"))
        assertNull(motionGlyph("D,B,C,C,8"))
        // ↓↓ (Sengoku 3's specials) is a straight down arrow with two heads.
        val tap2 = motionGlyph("22+A")!!
        assertEquals(2, tap2.heads.size)
        assertTrue(tap2.line.last().y > tap2.line.first().y)
        assertEquals(1, motionGlyph("236+A")!!.heads.size)
        assertNotNull(motionGlyph("[4]6+A")?.hold)
    }
}
