package de.melnichuk.ssi

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.GenericFilterBean
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

class SSIFilter: GenericFilterBean() {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain
    ) {
        val responseWrapper = ContentCachingResponseWrapper(response as HttpServletResponse)
        chain.doFilter(request, responseWrapper)

        Parser().parse(responseWrapper.contentInputStream).forEach {
            when(it) {
                is Segment.PlainTextSegment -> response.outputStream.write(it.text.toByteArray())
                is Segment.CommandSegment -> executeHttpRequest(it.params["virtual"] ?: throw IllegalArgumentException("param 'virtual' is missing."))
                    .copyTo(response.outputStream)
            }

            // send data as soon as available
            response.flushBuffer()
        }
    }
}

fun executeHttpRequest(uri: String): InputStream {
    val request = HttpRequest.newBuilder().GET().uri(URI(uri)).build()
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofInputStream())
        .body()
}