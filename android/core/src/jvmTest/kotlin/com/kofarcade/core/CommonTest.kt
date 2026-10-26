package com.kofarcade.core

import java.net.URLEncoder
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared stand-ins give what the JVM's own gave. */
class CommonTest {
    @Test
    fun crc32MatchesTheJvms() {
        val random = Random(7)
        repeat(50) {
            val bytes = random.nextBytes(random.nextInt(0, 3000))
            assertEquals(CRC32().apply { update(bytes) }.value, Crc32().apply { update(bytes) }.value)
        }
    }

    @Test
    fun urlEncodingMatchesTheJvms() {
        for (s in listOf("King of Fighters '97, The (NGM-2320).png", "Metal Slug X - Super Vehicle-001 (JAMMA PCB).png", "Mötley_Crüe ~ #1 & a+b=c.png")) {
            assertEquals(URLEncoder.encode(s, "UTF-8").replace("+", "%20"), urlEncode(s))
        }
    }

    @Test
    fun hex8MatchesFormat() {
        for (n in listOf(0L, 1L, 0xDEADBEEFL, 0xFFFFFFFFL, 0x1234L)) assertEquals("%08x".format(n), hex8(n))
    }
}
