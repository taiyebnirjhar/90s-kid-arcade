package com.kofarcade.core.replay

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchTest {
    /** The emulator's block (MatchRecorder::take), built here as the native code does. */
    private fun block(first: Int, ports: Int, masks: ShortArray, sums: List<Pair<Int, Long>>, state: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun int(v: Int) = repeat(4) { out.write((v ushr (8 * it)) and 0xFF) }
        out.write("90SKREC1".toByteArray())
        int(first); int(ports); int(masks.size / ports); int(sums.size); int(state.size)
        for (m in masks) { out.write(m.toInt() and 0xFF); out.write((m.toInt() ushr 8) and 0xFF) }
        for ((f, s) in sums) { int(f); int(s.toInt()); int((s ushr 32).toInt()) }
        out.write(state)
        return out.toByteArray()
    }

    /** A fight's inputs: buttons held for a while, then changed (as people press them). */
    private fun fight(frames: Int, ports: Int, seed: Int = 1): ShortArray {
        val r = Random(seed)
        val held = IntArray(ports)
        val left = IntArray(ports)
        return ShortArray(frames * ports) { i ->
            val p = i % ports
            if (left[p] == 0) { held[p] = r.nextInt(0, 1 shl 12); left[p] = r.nextInt(1, 20) }
            left[p]--
            held[p].toShort()
        }
    }

    @Test
    fun theEmulatorsBlockIsReadAsRecorded() {
        val masks = fight(300, 2)
        val sums = listOf(120 to -5L, 240 to Long.MAX_VALUE)
        val rec = Recording.parse(block(4000, 2, masks, sums, byteArrayOf(1, 2, 3)))!!
        assertEquals(4000, rec.first)
        assertEquals(300, rec.frames)
        assertEquals(masks[2 * 7 + 1].toInt() and 0xFFFF, rec.mask(4007, 1))
        assertEquals(sums, rec.sums)
        assertContentEquals(byteArrayOf(1, 2, 3), rec.state)
    }

    @Test
    fun aBlockThatIsntOneIsNothing() {
        assertNull(Recording.parse(ByteArray(0)))
        assertNull(Recording.parse(ByteArray(40)))
        val good = block(0, 2, fight(10, 2), emptyList(), byteArrayOf(9))
        assertNull(Recording.parse(good.copyOf(good.size - 1)), "cut short")
    }

    @Test
    fun aMatchFileGivesBackTheSameMatch() {
        val rec = Recording(120, 2, fight(10_000, 2, seed = 7), listOf(240 to 1L, 360 to -2L), Random(3).nextBytes(500))
        val info = MatchInfo("kof2002mp", "KOF 2002 Magic Plus", 1_700_000_000_000, rec.frames, room = true, players = listOf("Taiyeb", "Rafi"))
        val bytes = MatchFile.write(info, rec)
        val (readInfo, back) = MatchFile.read(bytes)
        assertEquals(info, readInfo)
        assertEquals(rec.first, back.first)
        assertEquals(rec.ports, back.ports)
        assertContentEquals(rec.masks, back.masks)
        assertEquals(rec.sums, back.sums)
        assertContentEquals(rec.state, back.state)
        assertEquals(info, MatchFile.readInfo(ByteArrayInputStream(bytes)))
    }

    @Test
    fun aThreeMinuteFightIsSmall() {
        // 3 minutes, 2 players; the state is about what a Neo Geo game's is.
        val rec = Recording(0, 4, fight(3 * 60 * 60, 4), emptyList(), ByteArray(300_000) { (it % 7).toByte() })
        val size = MatchFile.write(MatchInfo("kof2002mp", "KOF 2002", 0, rec.frames), rec).size
        assertTrue(size < 200_000, "3 minutes took $size bytes")
    }

    @Test
    fun aBrokenFileSaysSo() {
        assertFailsWith<IOException> { MatchFile.read("not a match".toByteArray()) }
        val rec = Recording(0, 1, fight(100, 1), emptyList(), byteArrayOf(1))
        val bytes = MatchFile.write(MatchInfo("g", "G", 0, 100), rec)
        assertFailsWith<IOException> { MatchFile.read(bytes.copyOf(bytes.size - 5)) }
    }

    @Test
    fun theNewestTwentyAreKeptAndStarredOnesAlways() {
        val matches = List(30) { SavedMatch("m$it", savedAt = it.toLong(), starred = it % 10 == 0) }
        val gone = MatchShelf.overflow(matches)
        // 27 unstarred: the 7 oldest go; starred ones (0, 10, 20) never.
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7").sorted(), gone.map { it.name }.sorted())
        assertTrue(MatchShelf.overflow(matches.take(5)).isEmpty())
    }

    @Test
    fun aReplayMatchesWhenEveryChecksumDoes() {
        val check = ReplayCheck(listOf(120 to 1L, 240 to 2L, 360 to 3L))
        check.see(120, 1L)
        check.see(120, 1L)  // the same one again: counted once
        check.see(130, 9L)  // a frame not saved: nothing to compare
        check.see(240, 2L)
        assertEquals(2, check.matched)
        assertTrue(check.matches)
        check.see(360, 4L)
        assertEquals(360, check.wrongAt)
        assertTrue(!check.matches)
        assertTrue(!ReplayCheck(emptyList()).matches, "nothing checked proves nothing")
    }
}
