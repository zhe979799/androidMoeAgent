package io.bigmoeonedge.example

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** App-private file tools for bounded inspection and scroll-style offset reads. */
object FileTools {
    private const val MAX_ENTRIES = 200
    private const val MAX_READ_BYTES = 16 * 1024

    suspend fun list(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val relative = args.optString("path", "").trim()
        val root = context.filesDir.canonicalFile
        val base = resolveInside(root, relative)
        require(base.isDirectory) { "path is not a directory" }
        val maxEntries = args.optInt("max_entries", MAX_ENTRIES).also {
            require(it in 1..MAX_ENTRIES) { "max_entries must be 1..$MAX_ENTRIES" }
        }
        val files = base.walkTopDown().filter { it.isFile }.take(maxEntries + 1).toList()
        JSONObject().apply {
            put("status", "ok")
            put("path", relative)
            put("truncated", files.size > maxEntries)
            put("files", JSONArray(files.take(maxEntries).map { file ->
                JSONObject()
                    .put("path", file.relativeTo(root).path.replace(File.separatorChar, '/'))
                    .put("bytes", file.length())
                    .put("modified_ms", file.lastModified())
            }))
        }
    }

    suspend fun read(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val relative = args.getString("path").trim()
        val offset = args.optLong("offset", 0L).also { require(it >= 0) { "offset must be non-negative" } }
        val maxBytes = args.optInt("max_bytes", 8 * 1024).also {
            require(it in 512..MAX_READ_BYTES) { "max_bytes must be 512..$MAX_READ_BYTES" }
        }
        val root = context.filesDir.canonicalFile
        val file = resolveInside(root, relative)
        require(file.isFile) { "path is not a file" }
        require(offset <= file.length()) { "offset is past end of file" }
        val bytes = ByteArray(maxBytes)
        val count = RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.read(bytes)
        }.coerceAtLeast(0)
        val next = offset + count
        JSONObject().apply {
            put("status", "ok")
            put("path", file.relativeTo(root).path.replace(File.separatorChar, '/'))
            put("offset", offset)
            put("next_offset", next)
            put("eof", next >= file.length())
            put("bytes", count)
            put("text", String(bytes, 0, count, Charsets.UTF_8))
        }
    }

    private fun resolveInside(root: File, relative: String): File {
        require(relative.length <= 512 && !relative.contains('\u0000')) { "invalid path" }
        val candidate = File(root, relative).canonicalFile
        val prefix = root.path + File.separator
        require(candidate.path == root.path || candidate.path.startsWith(prefix)) { "path must stay inside app files" }
        return candidate
    }
}
