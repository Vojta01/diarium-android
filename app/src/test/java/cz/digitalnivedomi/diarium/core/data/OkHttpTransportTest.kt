package cz.digitalnivedomi.diarium.core.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OkHttpTransportTest {

    @Test
    fun `body read IOException completes async callback exactly once`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(throwingBody())
                    .build()
            }
            .build()
        val callbackCount = AtomicInteger()
        val callbackFailure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)

        OkHttpTransport(client).enqueue(
            HttpRequest("GET", "https://example.test/", emptyMap()),
        ) { result ->
            callbackCount.incrementAndGet()
            callbackFailure.set(result.exceptionOrNull())
            completed.countDown()
        }

        assertTrue("body conversion failure left the async callback suspended", completed.await(3, TimeUnit.SECONDS))
        assertEquals(1, callbackCount.get())
        assertTrue(callbackFailure.get() is IOException)
        assertEquals("body read failed", callbackFailure.get()?.message)
    }

    private fun throwingBody(): ResponseBody = object : ResponseBody() {
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("body read failed")
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }
}
