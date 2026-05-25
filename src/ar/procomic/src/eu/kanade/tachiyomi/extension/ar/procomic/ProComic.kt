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
import java.security.Key
import java.security.MessageDigest
import java.security.spec.AlgorithmParameterSpec
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

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(::scrambledImageInterceptor)
        .addNetworkInterceptor(CookieInterceptor(domain, listOf("safe_browsing" to "off", "language" to "ar")))
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ar,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Upgrade-Insecure-Requests", "1")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private val rscHeaders = headersBuilder()
        .set("rsc", "1")
        .build()

    private fun checkAndThrow403(response: Response) {
        if (response.code == 403) {
            response.close()
            throw Exception("HTTP 403 - الرجاء استخدام 'Open in WebView' من القائمة لتجاوز الحماية.\n\nبعد فتح الموقع في WebView والعودة، ستتم المزامنة.")
        }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply { firstInstance<SortFilter>().state = 2 }
        return fetchSearchManga(page, "", filters)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply { firstInstance<SortFilter>().state = 1 }
        return fetchSearchManga(page, "", filters)
    }

    private val pageNumber = ConcurrentHashMap<String, Int>()

    private fun searchKey(query: String, filters: FilterList): String {
        val filterPart = filters.filterIsInstance<Filter<*>>().joinToString("|") { it.state.toString() }
        return "$query::$filterPart"
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            val path = url.pathSegments
            if (url.host == domain && path.size >= 4 && path[0] == "series") {
                val type = path[1]
                if (type !in SUPPORTED_TYPES) throw Exception("نوع غير مدعوم")
                val mangaId = path[2]
                val slug = path[3]
                val manga = SManga.create().apply { this@apply.url = "/series/$type/$mangaId/$slug" }
                return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
            } else throw Exception("رابط غير مدعوم")
        }

        val key = searchKey(query, filters)
        if (page == 1) pageNumber[key] = 1

        return client.newCall(searchMangaRequest(pageNumber[key]!!, query, filters))
            .asObservableSuccess()
            .doOnNext { checkAndThrow403(it) }
            .map { response ->
                val statusFilter = filters.firstInstance<StatusFilter>().selected
                val genreFilter = filters.firstInstance<GenreFilter>()
                val tagFilter = filters.firstInstance<TagFilter>()

                val data = response.parseAs<MetaData<BrowseManga>>()
                val mangas = data.data.asSequence()
                    .filter { manga -> manga.type in SUPPORTED_TYPES }
                    .filter { manga -> statusFilter == null || manga.progress == statusFilter }
                    .filter { manga -> genreFilter.included.isEmpty() || manga.metadata.genres.containsAll(genreFilter.included) }
                    .filter { manga -> genreFilter.excluded.none { it in manga.metadata.genres } }
                    .filter { manga -> tagFilter.included.isEmpty() || manga.metadata.tags.containsAll(tagFilter.included) }
                    .filter { manga -> tagFilter.excluded.none { it in manga.metadata.tags } }
                    .map { manga ->
                        SManga.create().apply {
                            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                            title = manga.title
                            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                                if (it.startsWith("/")) manga.cdn?.let { cdn -> "https://$cdn.$domain$it" } else it
                            }
                        }
                    }.toList()
                MangasPage(mangas, data.meta.hasNextPage())
            }
            .flatMap {
                if (it.mangas.isEmpty() && it.hasNextPage) {
                    pageNumber[key] = pageNumber[key]!! + 1
                    fetchSearchManga(pageNumber[key]!!, query, filters)
                } else {
                    if (!it.hasNextPage) pageNumber.remove(key)
                    Observable.just(it)
                }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            query.takeIf(String::isNotBlank)?.also { addQueryParameter("search", it) }
            filters.firstInstance<TypeFilter>().selected?.also { addQueryParameter("type", it) }
            addQueryParameter("sort", filters.firstInstance<SortFilter>().selected)
            filters.firstInstance<YearFilter>().selected?.also { addQueryParameter("year", it) }
        }.build()
        return GET(url, headers)
    }

    override fun getFilterList() = FilterList(
        TypeFilter(), SortFilter(), YearFilter(), StatusFilter(), GenreFilter(), TagFilter()
    )

    override fun mangaDetailsRequest(manga: SManga) = GET(getMangaUrl(manga), rscHeaders)
    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        checkAndThrow403(response)
        val manga = response.extractNextJs<Series>()!!.series
        return SManga.create().apply {
            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = buildString {
                manga.description?.also { append(it.trim(), "\n\n") }
                buildList {
                    addAll(manga.metadata.altTitles)
                    manga.metadata.originalTitle?.also { add(it) }
                }.also {
                    if (it.isNotEmpty()) {
                        append("عناوين بديلة\n")
                        it.forEach { title -> append("- ", title, "\n") }
                        append("\n")
                    }
                }
            }.trim()
            genre = buildList {
                add(manga.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                manga.metadata.year?.also { add(it) }
                manga.metadata.origin?.also { origin ->
                    add(origin.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                }
                when (manga.type) { "manga" -> add("مانجا"); "manhwa" -> add("مانها"); "manhua" -> add("مانهوا") }
                if (manga.metadata.genres.isNotEmpty()) {
                    val genreMap = genres.associate { it.second to it.first }
                    manga.metadata.genres.mapTo(this) { genreMap[it] ?: it }
                }
                if (manga.metadata.tags.isNotEmpty()) {
                    val tagsMap = tags.associate { it.second to it.first }
                    manga.metadata.tags.mapTo(this) { tagsMap[it] ?: it }
                }
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

    override fun chapterListParse(response: Response): List<SChapter> {
        checkAndThrow403(response)
        val data = response.extractNextJs<InitialChapters>()!!
        val chapters = data.initialChapters.toMutableList()
        val size = chapters.size
        var page = 2
        val type = response.request.url.pathSegments[1]
        val id = response.request.url.pathSegments[2]
        val slug = response.request.url.pathSegments[3]

        while (data.totalChapters > chapters.size) {
            val request = GET("$baseUrl/api/public/$type/$id/chapters?page=${page++}&limit=$size&order=desc", headers)
            val nextChapters = client.newCall(request).execute().also {
                if (!it.isSuccessful) { it.close(); throw Exception("HTTP ${it.code}") }
            }.parseAs<Data<List<Chapter>>>()
            chapters.addAll(nextChapters.data)
        }

        countViews(id)

        return chapters.filter { it.language == "AR" }.map { chapter ->
            SChapter.create().apply {
                url = "/series/$type/$id/$slug/${chapter.id}/${chapter.number}"
                name = buildString {
                    append("\u200F")
                    if (chapter.coins != null && chapter.coins > 0) append("🔒 ")
                    append("الفصل ")
                    append(chapter.number.toFloat().toString().substringBefore(".0"))
                    chapter.title?.trim()?.takeIf { it.isNotBlank() && it != chapter.number.trim() && it != chapter.number }?.let {
                        append(" \u200F- ", it)
                    }
                }
                scanlator = chapter.uploader ?: "\u200B"
                chapter_number = chapter.number.toFloat()
                date_upload = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).tryParse(chapter.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun pageListRequest(chapter: SChapter) = GET(getChapterUrl(chapter), rscHeaders)

    override fun getChapterUrl(chapter: SChapter): String {
        val url = if (chapter.url.startsWith("{")) chapter.url.parseAs<ChapterUrl>() else chapter.url
        return "$baseUrl$url"
    }

    override fun pageListParse(response: Response): List<Page> {
        checkAndThrow403(response)
        val responseBody = response.body.string()
        val imageData = responseBody.extractNextJsRsc<Images>() ?: throw Exception("فصل مدفوع أو لا يحتوي على صور")
        val seriesId = response.request.url.pathSegments[2]
        val chapterId = response.request.url.pathSegments[4]

        val images = imageData.images.toMutableList()
        val maps = mutableListOf<ScrambledData>()

        imageData.deferredMedia?.let { deferred ->
            if (deferred.requireTurnstile == true) {
                throw Exception("هذا الفصل يتطلب التحقق الأمني (Turnstile). الرجاء فتحه من متصفح عادي.")
            }
            val deferredUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("chapter-deferred-media")
                .addPathSegment(chapterId)
                .addQueryParameter("token", deferred.token)
                .apply { deferred.splitIndex?.let { addQueryParameter("split", it.toString()) } }
                .build()
            val deferredImages = client.newCall(GET(deferredUrl, headers)).execute().parseAs<Data<DeferredImages>>()
            images.addAll(deferredImages.data.images)
            maps.addAll(deferredImages.data.maps)
        }

        countViews(seriesId, chapterId)

        val chapterUrl = response.request.url.toString()
        val pages = mutableListOf<Page>()
        images.forEachIndexed { index, url -> pages.add(Page(index, chapterUrl, url)) }
        maps.forEachIndexed { index, data -> 
            val json = data.toJsonString()
            pages.add(Page(images.size + index, chapterUrl, "http://$SCRAMBLED_IMAGE_HOST/#$json"))
        }
        return pages
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().set("Referer", page.url).build())

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != SCRAMBLED_IMAGE_HOST) return chain.proceed(request)

        val chapterUrl = request.header("Referer")!!
        val cdn = when (chapterUrl.toHttpUrl().pathSegments[1]) { "manga" -> "cdn1"; "manhua" -> "cdn2"; else -> "cdn3" }

        val fragment = url.fragment ?: return chain.proceed(request)
        val scrambledData = fragment.parseAs<ScrambledData>()
        val scrambledImage = when (scrambledData) {
            is ScrambledImage -> scrambledData
            is ScrambledImageToken -> decodeScrambledImageToken(scrambledData)
            else -> throw IOException("Unknown scrambled data type")
        }

        val parts = scrambledImage.mode.split("_", limit = 2)
        val puzzleMode = parts[0]
        val layout = if (parts.size > 1) parts[1] else ""

        val width = scrambledImage.dim[0]
        val height = scrambledImage.dim[1]
        val orderedPieces = scrambledImage.order.map { scrambledImage.pieces[it] }

        val pieceBitmaps = runBlocking {
            orderedPieces.map { pieceUrl ->
                async(Dispatchers.IO.limitedParallelism(2)) {
                    var imgUrl = if (pieceUrl.startsWith("/")) "https://$cdn.$domain$pieceUrl" else pieceUrl
                    if (imgUrl.toHttpUrl().host.startsWith("cdn")) {
                        val payload = Url(url = imgUrl).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
                        val signHeaders = headersBuilder()
                            .set("Sec-Fetch-Site", "same-origin")
                            .set("Referer", chapterUrl)
                            .build()
                        val signRequest = POST("$baseUrl/api/cdn-image/sign", signHeaders, payload)
                        val response = client.newCall(signRequest).await()
                        if (response.isSuccessful) {
                            val token = response.parseAs<Token>()
                            imgUrl = imgUrl.toHttpUrl().newBuilder()
                                .addQueryParameter("token", token.token)
                                .addQueryParameter("expires", token.expires.toString())
                                .build().toString()
                        } else {
                            response.close()
                        }
                    }
                    val pieceRequest = request.newBuilder().url(imgUrl).build()
                    val response = client.newCall(pieceRequest).await()
                    response.body.use { body ->
                        val decoder = ImageDecoder.newInstance(body.byteStream()) ?: throw Exception("Failed to create decoder")
                        try { decoder.decode() ?: throw Exception("Failed to decode piece") }
                        finally { decoder.recycle() }
                    }
                }
            }.awaitAll()
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        try {
            when (puzzleMode) {
                "vertical" -> {
                    var x = 0f
                    for (bitmap in pieceBitmaps) {
                        canvas.drawBitmap(bitmap, x, 0f, null)
                        x += bitmap.width
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
                            if (idx < pieceBitmaps.size) {
                                canvas.drawBitmap(pieceBitmaps[idx], x, y, null)
                                x += pieceBitmaps[idx].width
                                maxH = maxOf(maxH, pieceBitmaps[idx].height.toFloat())
                            }
                        }
                        y += maxH
                    }
                }
                else -> throw IOException("Unknown puzzle mode: $puzzleMode")
            }
            val buffer = Buffer().apply { resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream()) }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(buffer.asResponseBody("image/jpg".toMediaType(), buffer.size))
                .build()
        } finally {
            pieceBitmaps.forEach { it.recycle() }
            resultBitmap.recycle()
        }
    }

    private val sessionKey = ConcurrentHashMap<Int, Pair<String, Long>>()
    private val sessionKeyLock = Any()

    private fun decodeScrambledImageToken(data: ScrambledImageToken): ScrambledImage {
        val decoded = String(urlSafeBase64(data.token), Charsets.UTF_8)
        val value = decoded.parseAs<ScrambledImageTokenValue>()

        val iv = urlSafeBase64(value.iv)
        val tag = urlSafeBase64(value.tag)
        val encryptedData = urlSafeBase64(value.data)

        val secretKey: Key = when (value.m) {
            "browser" -> {
                require(value.v == 2) { "Unsupported version: ${value.v}" }
                val input = "prochan-browser-map:2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2:${value.cid}"
                val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                SecretKeySpec(hash, "AES")
            }
            "browser_session" -> {
                require(value.v == 3) { "Unsupported version: ${value.v}" }
                synchronized(sessionKeyLock) {
                    val now = System.currentTimeMillis()
                    val existing = sessionKey[value.cid]
                    if (existing != null && existing.second > now) {
                        SecretKeySpec(urlSafeBase64(existing.first), "AES")
                    } else {
                        val request = GET("$baseUrl/chapter-map-session-key/${value.cid}", headers)
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) throw Exception("Failed to get session key")
                        val keyData = response.parseAs<Data<Key>>()
                        val key = keyData.data.key
                        sessionKey[value.cid] = Pair(key, now + 120000)
                        SecretKeySpec(urlSafeBase64(key), "AES")
                    }
                }
            }
            else -> throw Exception("Unknown method: ${value.m}")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decrypted = cipher.doFinal(encryptedData + tag)
        val json = String(decrypted, Charsets.UTF_8)
        return json.parseAs<ScrambledImage>()
    }

    private fun urlSafeBase64(data: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(data)

    private fun countViews(seriesId: String, chapterId: String? = null) {
        val userAgent = headers["User-Agent"]!!
        val payload = ViewsDto(
            chapterId = chapterId?.toInt(),
            contentId = seriesId.toInt(),
            deviceType = when {
                MOBILE_REGEX.containsMatchIn(userAgent) -> "mobile"
                TABLES_REGEX.containsMatchIn(userAgent) -> "tablet"
                else -> "desktop"
            },
            surface = if (chapterId == null) "series" else "chapter"
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)

        client.newCall(POST("$baseUrl/api/views", headers, payload)).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) { response.closeQuietly() }
            override fun onFailure(call: Call, e: okio.IOException) { Log.e(name, "Failed to count views", e) }
        })
    }

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val MOBILE_REGEX = Regex("mobile|android|iphone|ipad|ipod", RegexOption.IGNORE_CASE)
        private val TABLES_REGEX = Regex("tablet", RegexOption.IGNORE_CASE)
        private val SUPPORTED_TYPES = setOf("manga", "manhwa", "manhua")
    }
}
