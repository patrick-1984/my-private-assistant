package com.example

import com.example.data.ConfigurationPreset
import com.example.data.EndpointHealthStatus
import com.example.data.EndpointPreset
import com.example.data.EndpointSlot
import com.example.network.EndpointFailoverTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTrackerTest {

  private fun mkEp(id: String, url: String) =
    EndpointPreset(
      id = id,
      customName = id,
      endpointInput = url,
      apiKeyNotNeeded = true,
    )

  @Test
  fun `1 hour TTL skips redundant checks and resets timer on live transcription success`() {
    var clock = 1_000_000L
    val tracker = EndpointFailoverTracker(nowMs = { clock })
    val primary = mkEp("ep1", "http://10.0.0.1:8000")

    // Initially untested -> needs check
    assertTrue(tracker.needsFreshCheck(primary))

    // Initial check succeeds -> healthy, no check needed for 1 hour
    tracker.recordSuccess(primary)
    assertEquals(EndpointHealthStatus.HEALTHY, tracker.recordFor(primary).status)
    assertFalse(tracker.needsFreshCheck(primary))

    // Advance 50 minutes -> live transcription succeeds -> resets the 1-hour timer!
    clock += 50L * 60L * 1000L
    assertFalse(tracker.needsFreshCheck(primary))
    tracker.recordSuccess(primary)

    // Advance another 30 minutes (80 min total, 30 min since last live transcription) -> still fresh!
    clock += 30L * 60L * 1000L
    assertFalse(tracker.needsFreshCheck(primary))

    // Advance another 31 minutes (61 min since last live transcription) -> stale, needs check!
    clock += 31L * 60L * 1000L
    assertTrue(tracker.needsFreshCheck(primary))
  }

  @Test
  fun `primary degradation promotes secondary then tertiary and restores primary on recovery`() {
    var clock = 1_000_000L
    val tracker = EndpointFailoverTracker(nowMs = { clock })
    val primary = mkEp("ep1", "https://api.nvidia.example")
    val secondary = mkEp("ep2", "http://10.3.3.20:8000")
    val tertiary = mkEp("ep3", "http://10.3.3.21:8000")
    val endpoints = listOf(primary, secondary, tertiary)

    val cfg =
      ConfigurationPreset(
        id = "cfg1",
        primaryEndpointId = primary.id,
        secondaryEndpointId = secondary.id,
        tertiaryEndpointId = tertiary.id,
        allowEndpointFailover = true,
      )

    // Starts on Primary
    val sel1 = tracker.selectLeadingEndpoint(cfg, endpoints)!!
    assertEquals(EndpointSlot.PRIMARY, sel1.slot)
    assertFalse(sel1.isBackupActive)

    // Primary degrades -> Secondary becomes leading
    tracker.recordFailure(primary, "HTTP 503")
    val sel2 = tracker.selectLeadingEndpoint(cfg, endpoints)!!
    assertEquals(EndpointSlot.SECONDARY, sel2.slot)
    assertTrue(sel2.isBackupActive)
    assertEquals(listOf(secondary.id, tertiary.id, primary.id), tracker.orderedFailoverPlan(cfg, endpoints).map { it.second.id })

    // Recovery check respects 15s debounce, then becomes eligible
    assertFalse(tracker.needsRecoveryCheck(primary))
    clock += 16_000L
    assertTrue(tracker.needsRecoveryCheck(primary))

    // Secondary also degrades -> Tertiary becomes leading
    tracker.recordFailure(secondary, "Timeout")
    val sel3 = tracker.selectLeadingEndpoint(cfg, endpoints)!!
    assertEquals(EndpointSlot.TERTIARY, sel3.slot)
    assertTrue(sel3.isBackupActive)

    // Background check confirms Primary recovered -> Primary reclaims leading slot!
    tracker.recordSuccess(primary)
    val sel4 = tracker.selectLeadingEndpoint(cfg, endpoints)!!
    assertEquals(EndpointSlot.PRIMARY, sel4.slot)
    assertFalse(sel4.isBackupActive)
  }

  @Test
  fun `failover disabled strictly uses primary endpoint only`() {
    val tracker = EndpointFailoverTracker(nowMs = { 1000L })
    val primary = mkEp("ep1", "http://10.0.0.1:8000")
    val secondary = mkEp("ep2", "http://10.0.0.2:8000")
    val endpoints = listOf(primary, secondary)

    val cfg =
      ConfigurationPreset(
        id = "cfg1",
        primaryEndpointId = primary.id,
        secondaryEndpointId = secondary.id,
        allowEndpointFailover = false,
      )

    tracker.recordFailure(primary, "Down")
    val sel = tracker.selectLeadingEndpoint(cfg, endpoints)
    assertNotNull(sel)
    assertEquals(EndpointSlot.PRIMARY, sel!!.slot)
    assertFalse(sel.isBackupActive)
    assertEquals(listOf(primary.id), tracker.orderedFailoverPlan(cfg, endpoints).map { it.second.id })
  }
}
