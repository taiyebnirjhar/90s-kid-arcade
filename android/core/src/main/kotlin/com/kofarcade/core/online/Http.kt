package com.kofarcade.core.online

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class HttpResponse(val status: Int, val body: String)

/**
 * One HTTP request, blocking. Throws [IOException] when the server can't be
 * reached (no internet, a timeout); any answer, even an error, is a response.
 * The tests answer with a fake server.
 */
fun interface Http {
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/** The JDK's (and Android's) own client: no library needed for a few JSON calls. */
object JdkHttp : Http {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            headers.forEach(conn::setRequestProperty)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return HttpResponse(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
