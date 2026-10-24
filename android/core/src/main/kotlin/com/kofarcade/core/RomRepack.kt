package com.kofarcade.core

import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Older dumps of Capcom's CPS-3 games (Street Fighter III) keep each flash
// SIMM as one 8 MB file named by slot ("10", "20", "30", "31"...). FBNeo wants
// the chips on each SIMM as separate files ("sfiii3-simm1.0" to "1.3"). The
// bytes are the same, interleaved: a program SIMM (slots 1-2) spreads them
// across its 4 chips byte by byte; a graphics SIMM (3-7) across 2 chips byte
// by byte, then each chip's half in turn ("30" -> simm3.0-3.3, "31" ->
// simm3.4-3.7). Checked: all 40 chips of a 3rd Strike dump come out with
// FBNeo's CRCs.

private val SLOT = Regex("""^([1-7])([01])$""")

/** Splits one old-style SIMM file into FBNeo's chip files: chip index -> bytes. */
internal fun splitSimm(slot: Int, half: Int, data: ByteArray): Map<Int, ByteArray> {
    if (slot <= 2) {
        val lanes = Array(4) { ByteArray(data.size / 4) }
        for (i in data.indices) lanes[i % 4][i / 4] = data[i]
        return lanes.withIndex().associate { (k, lane) -> half * 4 + k to lane }
    }
    val lanes = Array(2) { ByteArray(data.size / 2) }
    for (i in data.indices) lanes[i % 2][i / 2] = data[i]
    val chip = lanes[0].size / 2
    return mapOf(
        half * 4 + 0 to lanes[0].copyOfRange(0, chip),
        half * 4 + 1 to lanes[1].copyOfRange(0, chip),
        half * 4 + 2 to lanes[0].copyOfRange(chip, lanes[0].size),
        half * 4 + 3 to lanes[1].copyOfRange(chip, lanes[1].size),
    )
}

/**
 * Rewrites an old-style CPS-3 dump at [input] into FBNeo's layout at [output].
 * Returns false (and writes nothing) when [input] isn't one: no slot files,
 * or no known version whose chips the split files all match. (Versions share
 * a BIOS chip, so the match is on every chip.)
 */
fun repackCps3(input: File, output: File): Boolean {
    ZipFile(input).use { zip ->
        val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
        val slots = entries.filter { SLOT.matches(it.name) }
        if (slots.isEmpty()) return false
        fun chips(e: ZipEntry): Map<String, ByteArray> {
            val (slot, half) = SLOT.find(e.name)!!.destructured
            val data = zip.getInputStream(e).use { it.readBytes() }
            return splitSimm(slot.toInt(), half.toInt(), data).mapKeys { (chip, _) -> "simm$slot.$chip" }
        }
        // First pass: each chip's CRC ("simm1.0" -> crc), one SIMM in memory at a time.
        val crcs = HashMap<String, String>()
        for (e in slots) {
            for ((name, bytes) in chips(e)) crcs[name] = "%08x".format(CRC32().apply { update(bytes) }.value)
        }
        val others = entries.filterNot { SLOT.matches(it.name) }.map { "%08x".format(it.crc) }.toSet()
        val set = romSets.firstOrNull { s ->
            val simms = s.files.filter { "-simm" in it.name }
            simms.isNotEmpty() && simms.size == crcs.size && s.files.any { it.crc in others } &&
                simms.all { crcs[it.name.substringAfter("-")] == it.crc }
        } ?: return false
        val prefix = set.files.first { "-simm" in it.name }.name.substringBefore("-simm")

        // Second pass: write the chips under FBNeo's names, the other files as they are.
        output.outputStream().buffered().use { out ->
            ZipOutputStream(out).use { z ->
                z.setLevel(Deflater.BEST_SPEED)
                for (e in entries.filterNot { SLOT.matches(it.name) }) {
                    z.putNextEntry(ZipEntry(e.name))
                    zip.getInputStream(e).use { it.copyTo(z) }
                    z.closeEntry()
                }
                for (e in slots) {
                    for ((name, bytes) in chips(e)) {
                        z.putNextEntry(ZipEntry("$prefix-$name"))
                        z.write(bytes)
                        z.closeEntry()
                    }
                }
            }
        }
    }
    return true
}
