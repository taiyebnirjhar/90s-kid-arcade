package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadTest {
    private var frame = 0L
    private val log = mutableListOf<String>()
    private val state = mutableMapOf<Key, Boolean>()
    private val pad = Pad(
        send = { k, v ->
            log += "${k.name.lowercase()}${if (v) 1 else 0}"
            state[k] = v
        },
        clock = { frame },
    )

    private fun runFrames(n: Int) = repeat(n) {
        frame++
        pad.onFrame()
    }

    @Test
    fun onlyChangesAreSent() {
        pad.setDir(6)
        pad.setDir(6)
        pad.press(Key.A, true)
        assertEquals(listOf("right1", "a1"), log)
    }

    @Test
    fun macroPlaysQcfAThenHandsBackToTheFinger() {
        pad.timing = Timing(step = 1, press = 1)
        pad.setDir(4)
        log.clear()
        pad.play("236+A")
        runFrames(10)
        assertEquals(listOf("down1", "left0", "right1", "down0", "a1", "right0", "a0", "left1"), log)
        assertEquals(true, state[Key.LEFT])
    }

    @Test
    fun stickMovesDuringAMacroDoNotInterruptIt() {
        pad.timing = Timing(step = 3, press = 1)
        pad.play("2+A")
        runFrames(1)
        pad.setDir(8)
        assertEquals(true, state[Key.DOWN])
        assertTrue(state[Key.UP] != true)
        runFrames(10)
        assertEquals(true, state[Key.UP])
    }

    @Test
    fun facingLeftMirrors() {
        pad.timing = Timing(step = 2, press = 1)
        pad.facingLeft = true
        pad.play("6+A")
        assertEquals(true, state[Key.LEFT])
    }

    @Test
    fun rapidFireTogglesEveryTwoFrames() {
        pad.setRapid(Key.A, true)
        log.clear()
        pad.press(Key.A, true)
        runFrames(6)
        pad.press(Key.A, false)
        assertEquals(listOf("a1", "a0", "a1", "a0"), log.filter { it.startsWith("a") })
    }

    @Test
    fun blockHoldsBackAndFollowsFacing() {
        pad.setDir(6) // stick forward
        pad.setHold(4) // block button: back wins over the stick
        assertEquals(true, state[Key.LEFT])
        assertTrue(state[Key.RIGHT] != true)
        pad.setHold(1) // slid down: low block
        assertEquals(true, state[Key.DOWN])
        pad.facingLeft = true
        pad.setHold(4) // facing left, back is right
        assertEquals(true, state[Key.RIGHT])
        pad.setHold(null) // let go: the stick is back in charge
        assertEquals(true, state[Key.RIGHT])
        assertTrue(state[Key.DOWN] != true)
    }

    @Test
    fun aTapDuringAMacroIsQueued() {
        pad.timing = Timing(step = 1, press = 1)
        pad.play("6+A")
        pad.play("4+B")
        runFrames(10)
        assertTrue(log.indexOf("b1") > log.indexOf("a1"))
    }

    /** The stick direction the game sees each frame, as numpad digits. */
    private fun dirNow(): Int {
        val h = if (state[Key.LEFT] == true) -1 else if (state[Key.RIGHT] == true) 1 else 0
        val v = if (state[Key.UP] == true) 1 else if (state[Key.DOWN] == true) -1 else 0
        return 5 + h + 3 * v
    }

    @Test
    fun runDoubleTapsThenHolds() {
        pad.timing = Timing(step = 2)
        pad.setDir(6) // already walking right
        pad.startRun()
        val seen = mutableListOf(dirNow())
        repeat(8) {
            runFrames(1)
            seen += dirNow()
        }
        // neutral, tap, tap, release, release, then held right
        assertEquals(listOf(5, 6, 6, 5, 5, 6, 6, 6, 6), seen)
        assertTrue(pad.running)
        // Steering: up while running goes up-right.
        pad.setDir(8)
        assertEquals(9, dirNow())
        pad.setDir(5)
        assertEquals(6, dirNow())
        pad.stopRun()
        assertEquals(5, dirNow())
    }

    @Test
    fun runGoesTheWayTheStickLastPointed() {
        pad.timing = Timing(step = 1)
        pad.setDir(1)
        pad.setDir(5)
        assertEquals(4, pad.lastHorizontal)
        pad.startRun()
        runFrames(5)
        assertEquals(4, dirNow())
        pad.releaseAll()
        assertEquals(false, pad.running)
        assertEquals(5, dirNow())
    }
}
