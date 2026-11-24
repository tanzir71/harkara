package io.github.tanzir71.harkara.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.settings.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckerTest {
    private val server = MockWebServer()
    private lateinit var settings: AppSettings
    private var clock = 1_000_000L

    private val latest = """
        {"version":"0.2.0","versionCode":200,"published":"2026-11-30T00:00:00Z",
         "apks":[{"abi":"arm64-v8a","url":"https://example.invalid/a.apk","bytes":24000000,"sha256":"ab"}],
         "notes":"Protocol core","extra":"ignored"}
    """.trimIndent()

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = AppSettings(context)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun checker(current: Int) = UpdateChecker(settings, current, server.url("/latest.json").toString(), now = { clock })

    @Test
    fun `newer release is offered`() = runBlocking {
        server.enqueue(MockResponse().setBody(latest))
        val result = checker(current = 100).checkIfDue()
        assertEquals("0.2.0", result?.version)
        assertEquals(200, result?.versionCode)
        assertEquals("arm64-v8a", result?.apks?.single()?.abi)
    }

    @Test
    fun `same or older release is not offered`() = runBlocking {
        server.enqueue(MockResponse().setBody(latest))
        assertNull(checker(current = 200).checkIfDue())
    }

    @Test
    fun `checks at most once a day`() = runBlocking {
        server.enqueue(MockResponse().setBody(latest))
        server.enqueue(MockResponse().setBody(latest))
        val checker = checker(current = 100)

        checker.checkIfDue()
        clock += 6 * 60 * 60 * 1000L
        val second = checker.checkIfDue()

        assertEquals(1, server.requestCount)
        assertEquals("0.2.0", second?.version)

        clock += 19 * 60 * 60 * 1000L
        checker.checkIfDue()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failures keep the last known release and do not crash`() = runBlocking {
        server.enqueue(MockResponse().setBody(latest))
        server.enqueue(MockResponse().setResponseCode(500))
        val checker = checker(current = 100)
        checker.checkIfDue()
        clock += 25 * 60 * 60 * 1000L

        val result = checker.checkIfDue()

        assertEquals("0.2.0", result?.version)
        assertEquals("0.2.0", checker(current = 100).available.value?.version)
    }
}
