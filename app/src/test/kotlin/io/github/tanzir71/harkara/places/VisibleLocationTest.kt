package io.github.tanzir71.harkara.places

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.ui.map.visibleLocationFixes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisibleLocationTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private class Source : LocationSource {
        val positions = MutableSharedFlow<Fix>(extraBufferCapacity = 4)
        var active = 0
        var starts = 0
        var cached: Fix? = null
        override fun lastKnown() = cached
        override fun updates(intervalMillis: Long): Flow<Fix> = flow {
            starts++
            active++
            try { emitAll(positions) } finally { active-- }
        }
    }

    @Test
    fun flowCreatedBeforeGrantIsNotPermanentlyEmpty() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 23.74
            longitude = 90.37
            time = 123L
        })
        val remembered = AndroidLocationSource(app).updates(10_000)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fix = withTimeout(1_000) { remembered.first() }
        assertEquals(LatLng(23.74, 90.37), fix.position)
    }

    @Test
    fun deniedFirstThenGrantOnResumeStartsAndPropagatesFix() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val source = Source()
            var permitted = false
            val values = mutableListOf<Fix?>()
            val job = backgroundScope.launch { visibleLocationFixes(source, owner.lifecycle, 10_000) { permitted }.collect { values += it } }
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(0, source.active)
            assertNull(values.last())

            owner.lifecycle.currentState = Lifecycle.State.CREATED
            permitted = true
            val cached = Fix(LatLng(23.74, 90.37), 80, 1)
            source.cached = cached
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, source.active)
            assertEquals(cached, values.last())
            val fresh = Fix(LatLng(23.75, 90.38), 20, 2)
            source.positions.emit(fresh)
            runCurrent()
            assertEquals(fresh, values.last())
            job.cancel()
            runCurrent()
            assertEquals(0, source.active)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pauseStopsGpsResumeRechecksPermissionAndDisposalClosesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val source = Source()
            var permitted = true
            val values = mutableListOf<Fix?>()
            val job = backgroundScope.launch { visibleLocationFixes(source, owner.lifecycle, 5_000) { permitted }.collect { values += it } }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(0, source.active)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, source.active)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(0, source.active)
            permitted = false
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(0, source.active)
            assertNull(values.last())
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            permitted = true
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, source.active)
            assertEquals(2, source.starts)
            job.cancel()
            runCurrent()
            assertEquals(0, source.active)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
