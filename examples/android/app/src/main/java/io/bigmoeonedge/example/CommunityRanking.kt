package io.bigmoeonedge.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A model entry returned by a community ranking source. Metadata is not a download promise. */
data class CommunityModel(
    val rank: Int,
    val name: String,
    val downloads: Long?,
    val likes: Long?,
    val updatedAt: String?,
    val description: String?,
    val url: String,
)

data class CommunityRanking(val source: String, val fetchedAtMs: Long, val models: List<CommunityModel>)

/**
 * Small adapter for domestic model-community discovery. The response parser accepts the several
 * envelopes used by ModelScope deployments so an API field rename does not blank the page. The
 * app only caches the current result in memory; model files are still downloaded through the
 * existing downloader after the user explicitly chooses a URL.
 */
object CommunityRankingRepository {
    const val MODELSCOPE = "ModelScope"
    private const val PAGE_SIZE = 20
    private const val MAX_BYTES = 1_000_000
    private const val ENDPOINT = "https://modelscope.cn/api/v1/trend"

    suspend fun fetch(source: String = MODELSCOPE, query: String = ""): Result<CommunityRanking> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(source == MODELSCOPE) { "暂不支持该社区" }
                val encoded = URLEncoder.encode(query.trim().take(80), "UTF-8")
                val separator = if (ENDPOINT.contains('?')) '&' else '?'
                val url = "$ENDPOINT${separator}Type=model&PageNumber=1&PageSize=$PAGE_SIZE" +
                    if (encoded.isNotEmpty()) "&Name=$encoded" else ""
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "BigMoeOnEdge/1")
                }
                try {
                    require(connection.responseCode in 200..299) { "社区接口 HTTP ${connection.responseCode}" }
                    val body = readLimited(connection).toString(Charsets.UTF_8)
                    parse(source, body)
                } finally {
                    connection.disconnect()
                }
            }
        }

    /** Parse without depending on one server-side envelope; used by the UI and JVM tests. */
    fun parse(source: String, body: String, nowMs: Long = System.currentTimeMillis()): CommunityRanking {
        val root = JSONObject(body)
        val list = findArray(root) ?: JSONArray()
        val models = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val name = firstString(item, "name", "ResourceDisplayCnName", "ResourceDisplay", "modelName", "model_name", "id") ?: continue
                val slug = firstString(item, "ResourceDisplay", "path", "modelId", "model_id", "id") ?: name
                val rank = firstInt(item, "rank", "ranking")
                    ?: (firstInt(item, "index")?.plus(1) ?: (size + 1))
                add(
                    CommunityModel(
                        rank = rank,
                        name = name,
                        downloads = firstLong(item, "downloads", "Downloads", "downloadCount", "download_count", "downloads_total"),
                        likes = firstLong(item, "likes", "Likes", "likeCount", "like_count", "stars"),
                        updatedAt = firstString(item, "updatedAt", "GmtModified", "updated_at", "lastUpdated"),
                        description = firstString(item, "description", "ResourceDisplayCnName", "summary", "intro"),
                        url = "https://modelscope.cn/models/${slug.trimStart('/')}",
                    )
                )
            }
        }.sortedWith(compareBy<CommunityModel> { it.rank }.thenByDescending { it.downloads ?: -1L })
            .take(PAGE_SIZE)
        return CommunityRanking(source, nowMs, models)
    }

    private fun findArray(root: JSONObject): JSONArray? {
        fun find(o: JSONObject): JSONArray? {
            val keys = o.keys().asSequence().toList()
            for (key in keys) {
                val value = o.opt(key)
                if (key.lowercase(Locale.US) in setOf("data", "models", "items", "results", "list", "trends")) {
                    when (value) {
                        is JSONArray -> return value
                        is JSONObject -> find(value)?.let { return it }
                    }
                }
            }
            return null
        }
        return find(root)
    }

    private fun valueFor(o: JSONObject, key: String): Any? = o.keys().asSequence()
        .firstOrNull { it.equals(key, ignoreCase = true) }
        ?.let(o::opt)

    private fun firstString(o: JSONObject, vararg keys: String): String? = keys.asSequence()
        .mapNotNull { key -> valueFor(o, key)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
        .firstOrNull()

    private fun firstLong(o: JSONObject, vararg keys: String): Long? = keys.asSequence()
        .mapNotNull { key -> valueFor(o, key)?.toString()?.replace(",", "")?.toLongOrNull() }
        .firstOrNull()

    private fun firstInt(o: JSONObject, vararg keys: String): Int? = firstLong(o, *keys)?.toInt()

    private fun readLimited(connection: HttpURLConnection): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        BufferedInputStream(connection.inputStream).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_BYTES) { "社区返回值过大" }
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }
}

fun formatCommunityCount(value: Long?): String = when {
    value == null -> "—"
    value >= 1_000_000 -> String.format(Locale.CHINA, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.CHINA, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
