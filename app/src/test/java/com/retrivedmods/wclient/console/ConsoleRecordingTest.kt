package com.retrivedmods.wclient.console

import com.retrivedmods.wtrace.WPacketStats
import com.retrivedmods.wtrace.WTrace
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConsoleRecordingTest {
    @Before fun setup() { WTrace.clear() }
    @After fun restore() {
        ConsoleSettings(recording = true).applyToTrace()
        WTrace.clear()
    }

    @Test fun captureIsOptInAndOffDoesNotCollectLogsOrCounters() {
        val settings = ConsoleSettings()
        assertFalse(settings.recording)
        settings.applyToTrace()
        WTrace.info("test", "not recorded")
        WTrace.packet(WPacketStats.TO_SERVER, "TestPacket", 10)
        assertEquals(0, WTrace.bufferSize())
        assertEquals(0L, WTrace.stats.totalToServer())
    }

    @Test fun stoppingRetainsExistingReportButDoesNotAppend() {
        ConsoleSettings(recording = true).applyToTrace()
        WTrace.info("test", "recorded before stop")
        WTrace.packet(WPacketStats.TO_SERVER, "BeforeStop", 10)
        val savedSize = WTrace.bufferSize()
        val savedCount = WTrace.stats.totalToServer()
        ConsoleSettings(recording = false).applyToTrace()
        WTrace.info("test", "ignored after stop")
        WTrace.packet(WPacketStats.TO_SERVER, "AfterStop", 10)
        assertEquals(savedSize, WTrace.bufferSize())
        assertEquals(savedCount, WTrace.stats.totalToServer())
    }

    @Test fun tracePreferencesDoNotOverrideMasterSwitchAndCanResume() {
        ConsoleSettings(recording = false, packetTrace = true, netTrace = true).applyToTrace()
        assertFalse(WTrace.enabled)
        ConsoleSettings(recording = true, packetTrace = false).applyToTrace()
        assertTrue(WTrace.enabled)
        WTrace.packet(WPacketStats.TO_SERVER, "CountOnly", 10)
        assertEquals(1L, WTrace.stats.totalToServer())
        assertEquals(0, WTrace.bufferSize())
    }
}
