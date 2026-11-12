package io.github.dacstage.artwork

import java.io.File
import java.io.IOException

/**
 * Bytes de carátulas por id (SHA-256): LRU en memoria acotada por bytes + disco con tope.
 * Los ficheros se llaman exactamente como el id (64 hex), así que no hay rutas controlables desde fuera.
 */
internal class ArtStore(
    private val dir: File,
    private val memoryBudgetBytes: Long = 24L * 1024 * 1024,
    private val diskBudgetBytes: Long = 100L * 1024 * 1024,
) {
    private class Entry(val bytes: ByteArray, val mime: String)

    private val memory = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var memoryBytes = 0L
    private var diskBytes = -1L

    @Synchronized
    fun put(id: String, bytes: ByteArray, mime: String) {
        putMemory(id, Entry(bytes, mime))
        writeDisk(id, bytes)
    }

    @Synchronized
    fun get(id: String): Pair<ByteArray, String>? {
        memory[id]?.let { return it.bytes to it.mime }
        val f = File(dir, id)
        if (!f.isFile) return null
        val bytes = try {
            f.readBytes()
        } catch (_: IOException) {
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        val mime = sniffMime(bytes) ?: OCTET
        putMemory(id, Entry(bytes, mime))
        return bytes to mime
    }

    @Synchronized
    fun contains(id: String): Boolean = memory.containsKey(id) || File(dir, id).isFile

    private fun putMemory(id: String, e: Entry) {
        memory.remove(id)?.let { memoryBytes -= it.bytes.size }
        if (e.bytes.size > memoryBudgetBytes) return // demasiado grande: solo disco
        memory[id] = e
        memoryBytes += e.bytes.size
        val it = memory.entries.iterator()
        while (memoryBytes > memoryBudgetBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == id) continue
            memoryBytes -= eldest.value.bytes.size
            it.remove()
        }
    }

    private fun writeDisk(id: String, bytes: ByteArray) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val target = File(dir, id)
            if (target.isFile && target.length() == bytes.size.toLong()) {
                target.setLastModified(System.currentTimeMillis())
                return
            }
            if (diskBytes < 0) diskBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
            val tmp = File(dir, "$id.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            diskBytes += bytes.size
            if (diskBytes > diskBudgetBytes) trimDisk(keep = id)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private fun trimDisk(keep: String) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        val goal = diskBudgetBytes * 9 / 10
        for (f in files) {
            if (total <= goal) break
            if (f.name == keep) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
        diskBytes = total
    }
}
