package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import rx.Observable
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class ProComic : HttpSource() {
    override val name = "ProComic"
    override val lang = "ar"
    private val domain = "procomic.net"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true
    override val versionId = 5

    override val client = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(::scrambledImageInterceptor)
        .addNetworkInterceptor(CookieInterceptor(domain, listOf("safe_browsing" to "off", "language" to "ar")))
        .addInterceptor { chain ->
            val original = chain.request()
            val newRequest = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ar,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .method(original.method, original.body)
                .build()
            chain.proceed(newRequest)
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private val rscHeaders = headersBuilder().set("rsc", "1").build()

    private fun check403(resp: Response) {
        if (resp.code == 403) {
            resp.close()
            throw Exception("HTTP 403 - استخدم 'Open in WebView' من القائمة")
        }
    }

    override fun fetchPopularManga(page: Int) = fetchSearchManga(page, "", getFilterList().apply { firstInstance<SortFilter>().state = 2 })
    override fun fetchLatestUpdates(page: Int) = fetchSearchManga(page, "", getFilterList().apply { firstInstance<SortFilter>().state = 1 })

    private val pageCache = ConcurrentHashMap<String, Int>()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            val path = url.pathSegments
            if (url.host == domain && path.size >= 4 && path[0] == "series") {
                val type = path[1]
                if (type !in setOf("manga", "manhwa", "manhua")) throw Exception("نوع غير مدعوم")
                val manga = SManga.create().apply { this.url = "/series/$type/${path[2]}/${path[3]}" }
                return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
            } else throw Exception("رابط غير مدعوم")
        }

        val key = "$query::${filters.filterIsInstance<Filter<*>>().joinToString("|") { it.state.toString() }}"
        if (page == 1) pageCache[key] = 1
        val curPage = pageCache[key] ?: 1

        return client.newCall(searchMangaRequest(curPage, query, filters))
            .asObservableSuccess()
            .doOnNext { check403(it) }
            .map { resp ->
                val data = resp.parseAs<MetaData<BrowseManga>>()
                val list = data.data.map { manga ->
                    SManga.create().apply {
                        url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                        title = manga.title
                        thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                            if (it.startsWith("/")) manga.cdn?.let { cdn -> "https://$cdn.$domain$it" } else it
                        }
                    }
                }
                MangasPage(list, data.meta.hasNextPage())
            }
            .flatMap { result ->
                if (result.mangas.isEmpty() && result.hasNextPage) {
                    pageCache[key] = curPage + 1
                    fetchSearchManga(curPage + 1, query, filters)
                } else {
                    if (!result.hasNextPage) pageCache.remove(key)
                    Observable.just(result)
                }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val builder = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder()
        builder.addQueryParameter("status", "approved")
        builder.addQueryParameter("limit", "18")
        builder.addQueryParameter("page", page.toString())
        if (query.isNotBlank()) builder.addQueryParameter("search", query)
        filters.firstInstance<TypeFilter>().selected?.let { builder.addQueryParameter("type", it) }
        builder.addQueryParameter("sort", filters.firstInstance<SortFilter>().selected)
        filters.firstInstance<YearFilter>().selected?.let { builder.addQueryParameter("year", it) }
        return GET(builder.build(), headers)
    }

    override fun getFilterList() = FilterList(TypeFilter(), SortFilter(), YearFilter(), StatusFilter(), GenreFilter(), TagFilter())

    override fun mangaDetailsRequest(manga: SManga) = GET(getMangaUrl(manga), rscHeaders)
    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(resp: Response): SManga {
        check403(resp)
        val manga = resp.extractNextJs<Series>()!!.series
        return SManga.create().apply {
            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = buildString {
                manga.description?.let { append(it.trim(), "\n\n") }
                val alt = buildList {
                    addAll(manga.metadata.altTitles)
                    manga.metadata.originalTitle?.let { add(it) }
                }
                if (alt.isNotEmpty()) {
                    append("عناوين بديلة\n")
                    alt.forEach { append("- ", it, "\n") }
                }
            }.trim()
            genre = buildList {
                add(manga.type)
                manga.metadata.year?.let { add(it) }
                manga.metadata.origin?.let { add(it) }
                when (manga.type) { "manga" -> add("مانجا"); "manhwa" -> add("مانها"); "manhua" -> add("مانهوا") }
                manga.metadata.genres.forEach { add(it) }
                manga.metadata.tags.forEach { add(it) }
            }.joinToString()
            status = when (manga.progress?.trim()) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                "متوقف" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.metadata.coverImage)?.let {
                if (it.startsWith("/")) manga.cdn?.let { cdn -> "https://$cdn.$domain$it" } else it
            }
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga) = GET(getMangaUrl(manga), rscHeaders)

    override fun chapterListParse(resp: Response): List<SChapter> {
        check403(resp)
        val data = resp.extractNextJs<InitialChapters>()!!
        val chapters = data.initialChapters.toMutableList()
        val limit = chapters.size
        var page = 2
        val type = resp.request.url.pathSegments[1]
        val id = resp.request.url.pathSegments[2]
        val slug = resp.request.url.pathSegments[3]

        while (data.totalChapters > chapters.size) {
            val req = GET("$baseUrl/api/public/$type/$id/chapters?page=$page&limit=$limit&order=desc", headers)
            val next = client.newCall(req).execute().also { if (!it.isSuccessful) { it.close(); throw Exception("HTTP ${it.code}") } }
                .parseAs<Data<List<Chapter>>>()
            chapters.addAll(next.data)
            page++
        }

        countViews(id)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        return chapters.filter { it.language == "AR" }.map { ch ->
            SChapter.create().apply {
                url = "/series/$type/$id/$slug/${ch.id}/${ch.number}"
                name = buildString {
                    append("\u200F")
                    if (ch.coins != null && ch.coins > 0) append("🔒 ")
                    append("الفصل ")
                    append(ch.number.toFloat().toString().substringBefore(".0"))
                    ch.title?.takeIf { it.isNotBlank() && it != ch.number.trim() && it != ch.number }?.let {
                        append(" \u200F- ", it)
                    }
                }
                scanlator = ch.uploader ?: "\u200B"
                chapter_number = ch.number.toFloat()
                date_upload = fmt.tryParse(ch.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter) = GET(getChapterUrl(chapter), rscHeaders)

    override fun getChapterUrl(chapter: SChapter): String {
        val part = if (chapter.url.startsWith("{")) chapter.url.parseAs<ChapterUrl>() else chapter.url
        return "$baseUrl$part"
    }

    override fun pageListParse(resp: Response): List<Page> {
        check403(resp)
        val body = resp.body.string()
        val imgData = body.extractNextJsRsc<Images>() ?: throw Exception("لا توجد صور")
        val seriesId = resp.request.url.pathSegments[2]
        val chapterId = resp.request.url.pathSegments[4]

        val images = imgData.images.toMutableList()
        val maps = mutableListOf<ScrambledData>()

        imgData.deferredMedia?.let { def ->
            if (def.requireTurnstile == true) throw Exception("يتطلب Turnstile، افتح في WebView أولاً")
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("chapter-deferred-media")
                .addPathSegment(chapterId)
                .addQueryParameter("token", def.token)
                .apply { def.splitIndex?.let { addQueryParameter("split", it.toString()) } }
                .build()
            val deferred = client.newCall(GET(url, headers)).execute().parseAs<Data<DeferredImages>>()
            images.addAll(deferred.data.images)
            maps.addAll(deferred.data.maps)
        }

        countViews(seriesId, chapterId)
        val chapterUrl = resp.request.url.toString()
        val pages = mutableListOf<Page>()
        images.forEachIndexed { i, u -> pages.add(Page(i, chapterUrl, u)) }
        maps.forEachIndexed { i, d -> pages.add(Page(images.size + i, chapterUrl, "http://$SCRAMBLED_IMAGE_HOST/#${d.toJsonString()}")) }
        return pages
    }

    override fun imageRequest(page: Page) = GET(page.imageUrl!!, headersBuilder().set("Referer", page.url).build())

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (url.host != SCRAMBLED_IMAGE_HOST) return chain.proceed(req)

        val chapterUrl = req.header("Referer")!!
        val cdn = when (chapterUrl.toHttpUrl().pathSegments[1]) { "manga" -> "cdn1"; "manhua" -> "cdn2"; else -> "cdn3" }
        val fragment = url.fragment ?: return chain.proceed(req)
        val scrambled = fragment.parseAs<ScrambledData>()
        val image = when (scrambled) {
            is ScrambledImage -> scrambled
            is ScrambledImageToken -> decodeToken(scrambled)
            else -> throw IOException("Unknown")
        }

        val parts = image.mode.split("_", limit = 2)
        val mode = parts[0]
        val layout = if (parts.size > 1) parts[1] else ""
        val width = image.dim[0]
        val height = image.dim[1]
        val pieces = image.order.map { image.pieces[it] }

        val bitmaps = runBlocking {
            pieces.map { piece ->
                async(Dispatchers.IO.limitedParallelism(2)) {
                    var imgUrl = if (piece.startsWith("/")) "https://$cdn.$domain$piece" else piece
                    if (imgUrl.toHttpUrl().host.startsWith("cdn")) {
                        val payload = Url(url = imgUrl).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
                        val signHeaders = headersBuilder().set("Sec-Fetch-Site", "same-origin").set("Referer", chapterUrl).build()
                        val sign = client.newCall(POST("$baseUrl/api/cdn-image/sign", signHeaders, payload)).await()
                        if (sign.isSuccessful) {
                            val token = sign.parseAs<Token>()
                            val newUrl = imgUrl.toHttpUrl().newBuilder()
                                .addQueryParameter("token", token.token)
                                .addQueryParameter("expires", token.expires.toString())
                                .build()
                            imgUrl = newUrl.toString()
                        }
                    }
                    val pieceReq = req.newBuilder().url(imgUrl).build()
                    val pieceResp = client.newCall(pieceReq).await()
                    pieceResp.body.use { body ->
                        val decoder = ImageDecoder.newInstance(body.byteStream()) ?: throw Exception("Decoder fail")
                        try { decoder.decode() ?: throw Exception("Decode fail") } finally { decoder.recycle() }
                    }
                }
            }.awaitAll()
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        try {
            when (mode) {
                "vertical" -> {
                    var x = 0f
                    for (bmp in bitmaps) {
                        canvas.drawBitmap(bmp, x, 0f, null)
                        x += bmp.width
                    }
                }
                "grid" -> {
                    val (cols, rows) = layout.split('x').map { it.toInt() }
                    var y = 0f
                    for (r in 0 until rows) {
                        var x = 0f
                        var maxH = 0f
                        for (c in 0 until cols) {
                            val idx = r * cols + c
                            if (idx < bitmaps.size) {
                                canvas.drawBitmap(bitmaps[idx], x, y, null)
                                x += bitmaps[idx].width
                                maxH = maxOf(maxH, bitmaps[idx].height.toFloat())
                            }
                        }
                        y += maxH
                    }
                }
                else -> throw IOException("Unknown mode")
            }
            val buffer = Buffer().apply { result.compress(Bitmap.CompressFormat.JPEG, 90, outputStream()) }
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(buffer.asResponseBody("image/jpg".toMediaType(), buffer.size))
                .build()
        } finally {
            bitmaps.forEach { it.recycle() }
            result.recycle()
        }
    }

    private val sessionCache = ConcurrentHashMap<Int, Pair<String, Long>>()
    private fun decodeToken(token: ScrambledImageToken): ScrambledImage {
        val raw = String(urlSafeBase64(token.token), Charsets.UTF_8)
        val value = raw.parseAs<ScrambledImageTokenValue>()
        val iv = urlSafeBase64(value.iv)
        val tag = urlSafeBase64(value.tag)
        val data = urlSafeBase64(value.data)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)

        val secret = when (value.m) {
            "browser" -> {
                require(value.v == 2)
                val input = "prochan-browser-map:2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2:${value.cid}"
                val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                SecretKeySpec(hash, "AES")
            }
            "browser_session" -> {
                require(value.v == 3)
                synchronized(sessionCache) {
                    val now = System.currentTimeMillis()
                    val exist = sessionCache[value.cid]
                    if (exist != null && exist.second > now) {
                        SecretKeySpec(urlSafeBase64(exist.first), "AES")
                    } else {
                        val req = GET("$baseUrl/chapter-map-session-key/${value.cid}", headers)
                        val resp = client.newCall(req).execute()
                        if (!resp.isSuccessful) throw Exception("Session key failed")
                        val key = resp.parseAs<Data<Key>>().data.key
                        sessionCache[value.cid] = Pair(key, now + 120000)
                        SecretKeySpec(urlSafeBase64(key), "AES")
                    }
                }
            }
            else -> throw Exception("Unknown method")
        }
        cipher.init(Cipher.DECRYPT_MODE, secret, spec)
        val decrypted = cipher.doFinal(data + tag)
        return String(decrypted, Charsets.UTF_8).parseAs()
    }

    private fun urlSafeBase64(data: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(data)

    private fun countViews(seriesId: String, chapterId: String? = null) {
        val ua = headers["User-Agent"]!!
        val payload = ViewsDto(
            chapterId = chapterId?.toInt(),
            contentId = seriesId.toInt(),
            deviceType = when {
                Regex("mobile|android", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> "mobile"
                Regex("tablet", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> "tablet"
                else -> "desktop"
            },
            surface = if (chapterId == null) "series" else "chapter"
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(POST("$baseUrl/api/views", headers, payload)).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) { response.closeQuietly() }
            override fun onFailure(call: Call, e: okio.IOException) { Log.e(name, "Views failed", e) }
        })
    }

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
