package io.bigmoeonedge.example

import android.content.Context
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/** Bounded search adapters. Results are data only; the agent never receives a page as instructions. */
object WebSearchTools {
    private const val MAX_QUERY_CHARS = 256
    private const val MAX_RESULTS = 5
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 12_000

    suspend fun execute(context: Context, name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = args.getString("query").trim()
        require(query.length in 1..MAX_QUERY_CHARS) { "query must be 1..$MAX_QUERY_CHARS characters" }
        val limit = args.optInt("limit", MAX_RESULTS).also { require(it in 1..MAX_RESULTS) { "limit must be 1..$MAX_RESULTS" } }
        when (name) {
            "search_baidu" -> searchHtml("baidu", "www.baidu.com", "https://www.baidu.com/s?wd=${encode(query)}&rn=$limit", query, limit)
            "search_bing" -> searchHtml("bing", "www.bing.com", "https://www.bing.com/search?q=${encode(query)}&count=$limit&setlang=zh-CN", query, limit)
            "search_exa" -> searchExa(context, query, limit)
            else -> error("unknown search tool")
        }
    }

    private fun searchHtml(provider: String, host: String, rawUrl: String, query: String, limit: Int): JSONObject {
        val body = request("GET", rawUrl, host, null, null)
        val results = when (provider) {
            "bing" -> parseBing(body)
            else -> parseBaidu(body)
        }.take(limit)
        return resultEnvelope(provider, query, results)
    }

    private fun searchExa(context: Context, query: String, limit: Int): JSONObject {
        val apiKey = SearchPreferences.loadExaApiKey(context)
        require(apiKey.isNotBlank()) { "Exa API Key 尚未在工具配置中填写" }
        val request = JSONObject()
            .put("query", query)
            .put("type", "auto")
            .put("numResults", limit)
            .put("contents", JSONObject().put("highlights", JSONObject().put("maxCharacters", 800)))
        val body = request("POST", "https://api.exa.ai/search", "api.exa.ai", apiKey, request.toString())
        val root = JSONObject(body)
        val results = buildList {
            val items = root.optJSONArray("results") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                add(
                    JSONObject()
                        .put("title", item.optString("title"))
                        .put("url", item.optString("url"))
                        .put("snippet", item.optString("summary").ifBlank {
                            item.optJSONArray("highlights")?.optString(0).orEmpty()
                        }),
                )
            }
        }
        return resultEnvelope("exa", query, results)
    }

    private fun request(method: String, rawUrl: String, expectedHost: String, apiKey: String?, body: String?): String {
        val uri = URI(rawUrl)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443)) {
            "search endpoint must be HTTPS on port 443"
        }
        require(uri.host.equals(expectedHost, ignoreCase = true)) { "unexpected search endpoint" }
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", if (method == "POST") "application/json" else "text/html,application/xhtml+xml")
            setRequestProperty("User-Agent", "BigMoeOnEdge-Agent/1")
            apiKey?.let { setRequestProperty("x-api-key", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode in 200..299) { "search endpoint HTTP ${connection.responseCode}" }
            val length = connection.contentLengthLong
            require(length <= MAX_BODY_BYTES || length < 0) { "search response is too large" }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_BODY_BYTES) { "search response is too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return String(bytes, responseCharset(connection.contentType))
        } finally {
            connection.disconnect()
        }
    }

    private fun responseCharset(contentType: String?): Charset {
        val name = Regex("charset\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.groupValues?.getOrNull(1)?.trim()?.trim('"')
        return runCatching { if (name.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun parseBing(body: String): List<JSONObject> {
        val itemPattern = Regex(
            """<li[^>]*class=[\"'][^\"']*b_algo[^\"']*[\"'][^>]*>(.*?</li>)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return itemPattern.findAll(body).mapNotNull { match ->
            val item = match.groupValues[1]
            val link = Regex("""<h2[^>]*>\\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(item)
                ?: return@mapNotNull null
            JSONObject()
                .put("title", cleanHtml(link.groupValues[2]))
                .put("url", link.groupValues[1])
                .put("snippet", cleanHtml(Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(item)?.groupValues?.getOrNull(1).orEmpty()))
        }.toList()
    }

    private fun parseBaidu(body: String): List<JSONObject> {
        val linkPattern = Regex("""<a[^>]+href=[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return linkPattern.findAll(body).mapNotNull { match ->
            val url = match.groupValues[1]
            val title = cleanHtml(match.groupValues[2])
            if (title.length < 2 || url.contains("baidu.com", ignoreCase = true)) null
            else JSONObject().put("title", title).put("url", url).put("snippet", "")
        }.distinctBy { it.optString("url") }.toList()
    }

    private fun cleanHtml(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(1000)

    private fun resultEnvelope(provider: String, query: String, results: List<JSONObject>): JSONObject = JSONObject().apply {
        put("status", "ok")
        put("provider", provider)
        put("query", query)
        put("result_count", results.size)
        put("results", JSONArray(results))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
