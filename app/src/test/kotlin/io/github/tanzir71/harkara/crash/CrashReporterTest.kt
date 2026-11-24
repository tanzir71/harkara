package io.github.tanzir71.harkara.crash

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `report says no crash until one is recorded`() {
        val reporter = CrashReporter(File(folder.root, "crash/last.txt"), "0.1.0", 100)
        val report = reporter.report()
        assertTrue(report.startsWith("Harkara 0.1.0 (100)"))
        assertTrue(report.contains("No crash recorded."))
    }

    @Test
    fun `recorded crash appears in the copied report`() {
        val reporter = CrashReporter(File(folder.root, "crash/last.txt"), "0.1.0", 100)
        reporter.record("main", IllegalStateException("relay pool exploded"))

        val report = reporter.report()
        assertTrue(report.contains("Crash at"))
        assertTrue(report.contains("IllegalStateException: relay pool exploded"))

        reporter.clear()
        assertTrue(reporter.report().contains("No crash recorded."))
    }
}
