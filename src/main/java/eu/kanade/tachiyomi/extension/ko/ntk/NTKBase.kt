package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

abstract class NTKBase(
    override val name: String,
    private val typeParam: String,
) : HttpSource() {

    override val lang = "ko"

    override val supportsLatest = true

    override val baseUrl = "https://ntk01.com"

    protected val rootUrl = "https://ntk01.com"

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    protected val apiHeaders: Headers by lazy {
        headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    protected inline fun <reified T> Response.parseAs(): T =
        json.decodeFromString(body.string())

    protected val json: Json by injectLazy()

    protected val PAGE_SIZE = 42

    abstract val webViewPath: String

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonElement = json.parseToJsonElement(response.body.string())
        val data = jsonElement.jsonObject["data"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = data.map { element ->
            SManga.create().apply {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                url = "/work/$id"
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content?.let { "$rootUrl$it" }
            }
        }

        val total = jsonElement.jsonObject["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = page * PAGE_SIZE < total

        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.endsWith("/search")) {
            val jsonElement = json.parseToJsonElement(response.body.string())
            val data = jsonElement.jsonObject["data"]?.jsonArray ?: return MangasPage(emptyList(), false)

            val mangas = data.map { element ->
                SManga.create().apply {
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: ""
                    url = "/work/$id"
                    title = obj["title"]?.jsonPrimitive?.content ?: ""
                    thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content?.let { "$rootUrl$it" }
                }
            }
            return MangasPage(mangas, false)
        }
        return popularMangaParse(response)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val html = response.body.string()
        val jsonStr = html.substringAfter("window.__INITIAL_STATE__ = ").substringBefore(";</script>")
        val jsonElement = json.parseToJsonElement(jsonStr)
        val work = jsonElement.jsonObject["work"]?.jsonObject ?: return@apply

        title = work["title"]?.jsonPrimitive?.content ?: ""
        thumbnail_url = work["thumbnail"]?.jsonPrimitive?.content?.let { "$rootUrl$it" }
        author = work["author"]?.jsonPrimitive?.content
        artist = work["artist"]?.jsonPrimitive?.content
        description = work["synopsis"]?.jsonPrimitive?.content
        status = when (work["status"]?.jsonPrimitive?.content) {
            "ongoing" -> SManga.ONGOING
            "end" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val jsonStr = html.substringAfter("window.__INITIAL_STATE__ = ").substringBefore(";</script>")
        val jsonElement = json.parseToJsonElement(jsonStr)
        val episodes = jsonElement.jsonObject["episodes"]?.jsonArray ?: return emptyList()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())

        return episodes.map { element ->
            SChapter.create().apply {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                url = "/episode/$id"
                name = obj["title"]?.jsonPrimitive?.content ?: ""
                date_upload = obj["publishedAt"]?.jsonPrimitive?.content?.let {
                    try { sdf.parse(it)?.time } catch (_: Exception) { 0L }
                } ?: 0L
            }
        }.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val jsonStr = html.substringAfter("window.__INITIAL_STATE__ = ").substringBefore(";</script>")
        val jsonElement = json.parseToJsonElement(jsonStr)
        val episode = jsonElement.jsonObject["episode"]?.jsonObject ?: return emptyList()
        val images = episode["images"]?.jsonArray ?: return emptyList()

        return images.mapIndexed { index, element ->
            Page(index, "", "$rootUrl${element.jsonPrimitive.content}")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
