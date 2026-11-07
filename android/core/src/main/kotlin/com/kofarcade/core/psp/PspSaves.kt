package com.kofarcade.core.psp

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A PSP game's saves as one file, to keep or to pass on (the owner,
 * 2026-11-07: "we should able to load or save game data, so people can play
 * from that stage"): the game's own saves (PPSSPP's memory stick,
 * `PSP/SAVEDATA/<disc id>…` folders) and where it was left (the app's place
 * state), in a zip. Loading one puts both back, so the game goes on from
 * that stage; what was there before is kept aside, never thrown away.
 */
object PspSaves {
    const val MANIFEST = "90s-kid-arcade-psp-save.txt"
    private const val SAVEDATA = "savedata/"
    private const val PLACE = "place.state"
    // A save bigger than this isn't a PSP save (the biggest are a few MB; a place state ~40 MB).
    private const val MAX_BYTES = 200L * 1024 * 1024

    /**
     * The disc's id ("UCUS98737") from a plain ISO: UMD_DATA.BIN at the
     * disc's root starts with it ("UCUS-98737|…"). Null for a compressed
     * image (CSO, CHD) or anything else it can't read; then every save
     * folder goes in the file.
     */
    fun discId(iso: File): String? = runCatching {
        if (!iso.name.lowercase().endsWith(".iso")) return null
        RandomAccessFile(iso, "r").use { f ->
            fun read(at: Long, n: Int): ByteArray = ByteArray(n).also { f.seek(at); f.readFully(it) }
            fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
            val pvd = read(16L * 2048, 2048)
            if (pvd[0].toInt() != 1 || String(pvd, 1, 5) != "CD001") return null
            val rootLba = le32(pvd, 156 + 2)
            val rootSize = le32(pvd, 156 + 10).coerceAtMost(64 * 1024)
            val root = read(rootLba.toLong() * 2048, rootSize)
            var i = 0
            while (i < root.size) {
                val len = root[i].toInt() and 0xFF
                if (len == 0) {
                    i = (i / 2048 + 1) * 2048
                    continue
                }
                val nameLen = root[i + 32].toInt() and 0xFF
                val name = String(root, i + 33, nameLen).substringBefore(';')
                if (name.equals("UMD_DATA.BIN", ignoreCase = true)) {
                    val text = String(read(le32(root, i + 2).toLong() * 2048, 32))
                    val id = text.substringBefore('|').replace("-", "")
                    return if (Regex("[A-Z]{4}[0-9]{5}").matches(id)) id else null
                }
                i += len
            }
            null
        }
    }.getOrNull()

    /** The save folders of [id] in [savedata] (all of them when the id isn't known). */
    fun folders(savedata: File, id: String?): List<File> =
        savedata.listFiles()?.filter { it.isDirectory && (id == null || it.name.startsWith(id)) }?.sortedBy { it.name }.orEmpty()

    /** What's in a save file, from its manifest. */
    data class Info(val title: String, val discId: String?, val folders: List<String>, val hasPlace: Boolean)

    /**
     * Writes [title]'s saves to [out]: its save folders from [savedata] and,
     * if there is one, [place] (where the game was left). Returns what went in.
     */
    fun export(out: OutputStream, title: String, id: String?, savedata: File, place: File?): Info {
        val dirs = folders(savedata, id)
        val withPlace = place != null && place.isFile
        if (dirs.isEmpty() && !withPlace) throw IOException("Nothing saved yet for $title: save in the game, or play a while, first")
        ZipOutputStream(out.buffered()).use { zip ->
            val info = Info(title, id, dirs.map { it.name }, withPlace)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(info).toByteArray())
            zip.closeEntry()
            for (dir in dirs) {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(SAVEDATA + dir.name + "/" + f.relativeTo(dir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (withPlace) {
                zip.putNextEntry(ZipEntry(PLACE))
                place!!.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            return info
        }
    }

    /**
     * Loads a save file from [input] for the game whose disc is [id]: its
     * save folders into [savedata], its place into [place]. What was there
     * (a folder of the same name, the old place) moves into [backup] first.
     * Throws if it isn't a save file, or it's another game's.
     */
    fun import(input: InputStream, id: String?, savedata: File, place: File, backup: File): Info {
        val staging = File(backup.parentFile, ".import-${System.nanoTime()}").apply { mkdirs() }
        try {
            var info: Info? = null
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    val name = e.name
                    // Only our own names, nothing that could reach outside the folders.
                    if (e.isDirectory || name.contains("..") || name.startsWith("/") || name.contains('\\')) return@forEach
                    if (name == MANIFEST) {
                        info = parse(zip.readBytes().toString(Charsets.UTF_8))
                        return@forEach
                    }
                    if (name != PLACE && !name.startsWith(SAVEDATA)) return@forEach
                    val to = File(staging, name)
                    to.parentFile.mkdirs()
                    to.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("That file is too big to be a PSP save")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            val got = info ?: throw IOException("That isn't a save file from this app")
            if (id != null && got.discId != null && got.discId != id) {
                throw IOException("That save is for ${got.title} (${got.discId}), not this game")
            }
            // What's there now, kept aside; then the loaded one in its place.
            backup.mkdirs()
            val incoming = File(staging, SAVEDATA).listFiles()?.filter { it.isDirectory }.orEmpty()
            savedata.mkdirs()
            for (dir in incoming) {
                val here = File(savedata, dir.name)
                if (here.exists()) here.copyRecursively(File(backup, "savedata/${dir.name}"), overwrite = true)
                here.deleteRecursively()
                if (!dir.renameTo(here)) dir.copyRecursively(here, overwrite = true)
            }
            val newPlace = File(staging, PLACE)
            if (newPlace.isFile) {
                if (place.isFile) place.copyTo(File(backup, place.name), overwrite = true)
                place.parentFile?.mkdirs()
                newPlace.copyTo(place, overwrite = true)
            }
            return got.copy(folders = incoming.map { it.name }, hasPlace = newPlace.isFile)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun manifest(i: Info) = buildString {
        appendLine("title=${i.title.replace('\n', ' ')}")
        appendLine("disc=${i.discId.orEmpty()}")
        appendLine("folders=${i.folders.joinToString(",")}")
        appendLine("place=${i.hasPlace}")
    }

    private fun parse(text: String): Info {
        val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return Info(
            title = m["title"].orEmpty().ifEmpty { "a PSP game" },
            discId = m["disc"]?.ifEmpty { null },
            folders = m["folders"].orEmpty().split(',').filter { it.isNotEmpty() },
            hasPlace = m["place"] == "true",
        )
    }
}
