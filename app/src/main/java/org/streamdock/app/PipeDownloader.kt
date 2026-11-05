package org.streamdock.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class PipeDownloader : Downloader() {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (key, values) ->
            builder.removeHeader(key)
            values.forEach { builder.addHeader(key, it) }
        }
        val body = request.dataToSend()?.toRequestBody(
            request.headers().entries.firstOrNull { it.key.equals("Content-Type", true) }
                ?.value?.firstOrNull()?.toMediaTypeOrNull())
        val method = request.httpMethod()
        builder.method(method, body ?: if (method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null)
        client.newCall(builder.build()).execute().use {
            if (it.code == 429) throw ReCaptchaException("Too many requests; try later.", request.url())
            return Response(it.code, it.message, it.headers.toMultimap(), it.body?.string(), it.request.url.toString())
        }
    }
}
