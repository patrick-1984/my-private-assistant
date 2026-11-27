package com.example.network

import android.content.Context
import com.example.data.AppSettingsPreset
import com.example.data.ConfigurationPreset
import com.example.data.EndpointHealthRecord
import com.example.data.EndpointHealthStatus
import com.example.data.EndpointPreset
import com.example.data.EndpointSlot
import com.example.data.ServerFamily
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActiveEndpointSelection(
  val slot: EndpointSlot,
  val endpoint: EndpointPreset,
  val totalUsableSlots: Int,
  val isBackupActive: Boolean,
)

/**
 * Pure, clock-injectable failover and 1-hour health-TTL state machine.
 * - Any successful health check or real/live transcription resets the 1-hour timer.
 * - When failover is enabled, the leading endpoint is the highest-priority usable slot
 *   (Primary -> Secondary -> Tertiary) that is not currently marked DEGRADED.
 * - When a backup (Secondary or Tertiary) is leading because Primary is DEGRADED,
 *   background recovery checks test whether Primary is back online without delaying audio.
 */
class EndpointFailoverTracker(
  initialRecords: Map<String, EndpointHealthRecord> = emptyMap(),
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val onPersist: (Map<String, EndpointHealthRecord>) -> Unit = {},
) {
  private val lock = Any()
  private val _records = MutableStateFlow(initialRecords)
  val records: StateFlow<Map<String, EndpointHealthRecord>> = _records.asStateFlow()

  fun recordFor(endpoint: EndpointPreset): EndpointHealthRecord {
    val stored = _records.value[endpoint.id] ?: return EndpointHealthRecord()
    return if (stored.fingerprint == endpoint.healthFingerprint) {
      stored
    } else {
      EndpointHealthRecord()
    }
  }

  fun needsFreshCheck(endpoint: EndpointPreset): Boolean {
    val now = nowMs()
    val rec = recordFor(endpoint)
    return when (rec.status) {
      EndpointHealthStatus.UNKNOWN -> true
      EndpointHealthStatus.DEGRADED ->
        (now - rec.lastCheckedAtMs) >= RECOVERY_CHECK_MIN_INTERVAL_MS
      EndpointHealthStatus.HEALTHY ->
        (now - rec.lastSuccessAtMs) >= HEALTH_CHECK_TTL_MS
    }
  }

  fun needsRecoveryCheck(endpoint: EndpointPreset): Boolean {
    val now = nowMs()
    val rec = recordFor(endpoint)
    return rec.status == EndpointHealthStatus.DEGRADED &&
      (now - rec.lastCheckedAtMs) >= RECOVERY_CHECK_MIN_INTERVAL_MS
  }

  fun recordSuccess(endpoint: EndpointPreset) {
    val now = nowMs()
    synchronized(lock) {
      val next = _records.value.toMutableMap()
      next[endpoint.id] =
        EndpointHealthRecord(
          status = EndpointHealthStatus.HEALTHY,
          lastCheckedAtMs = now,
          lastSuccessAtMs = now,
          lastError = null,
          fingerprint = endpoint.healthFingerprint,
        )
      _records.value = next
      onPersist(next)
    }
  }

  fun recordFailure(endpoint: EndpointPreset, reason: String) {
    val now = nowMs()
    synchronized(lock) {
      val prev = recordFor(endpoint)
      val next = _records.value.toMutableMap()
      next[endpoint.id] =
        EndpointHealthRecord(
          status = EndpointHealthStatus.DEGRADED,
          lastCheckedAtMs = now,
          lastSuccessAtMs = prev.lastSuccessAtMs,
          lastError = reason.take(120),
          fingerprint = endpoint.healthFingerprint,
        )
      _records.value = next
      onPersist(next)
    }
  }

  fun selectLeadingEndpoint(
    config: ConfigurationPreset,
    endpoints: List<EndpointPreset>,
  ): ActiveEndpointSelection? {
    val usable = config.orderedUsableSlots(endpoints)
    if (usable.isEmpty()) return null

    if (!config.allowEndpointFailover || usable.size == 1) {
      val (slot, ep) = usable.first()
      return ActiveEndpointSelection(
        slot = slot,
        endpoint = ep,
        totalUsableSlots = usable.size,
        isBackupActive = false,
      )
    }

    val chosen =
      usable.firstOrNull { (_, ep) -> recordFor(ep).status != EndpointHealthStatus.DEGRADED }
        ?: usable.first()

    return ActiveEndpointSelection(
      slot = chosen.first,
      endpoint = chosen.second,
      totalUsableSlots = usable.size,
      isBackupActive = chosen.first != EndpointSlot.PRIMARY,
    )
  }

  fun orderedFailoverPlan(
    config: ConfigurationPreset,
    endpoints: List<EndpointPreset>,
  ): List<Pair<EndpointSlot, EndpointPreset>> {
    val usable = config.orderedUsableSlots(endpoints)
    if (usable.isEmpty()) return emptyList()
    if (!config.allowEndpointFailover) return listOf(usable.first())

    val healthyOrUnknown =
      usable.filter { (_, ep) -> recordFor(ep).status != EndpointHealthStatus.DEGRADED }
    val degraded =
      usable.filter { (_, ep) -> recordFor(ep).status == EndpointHealthStatus.DEGRADED }
    return healthyOrUnknown + degraded
  }

  companion object {
    const val HEALTH_CHECK_TTL_MS = 60L * 60L * 1000L // 1 hour
    const val RECOVERY_CHECK_MIN_INTERVAL_MS = 15_000L
  }
}

class EndpointHealthManager private constructor(context: Context) {

  private val settingsRepo = SettingsRepository.get(context.applicationContext)
  private val endpointClient = EndpointClient()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val tracker =
    EndpointFailoverTracker(
      initialRecords = settingsRepo.loadEndpointHealthRecords(),
      onPersist = { records -> settingsRepo.saveEndpointHealthRecords(records) },
    )

  val records: StateFlow<Map<String, EndpointHealthRecord>> = tracker.records

  private val _checkingEndpointIds = MutableStateFlow<Set<String>>(emptySet())
  val checkingEndpointIds: StateFlow<Set<String>> = _checkingEndpointIds.asStateFlow()

  fun recordSuccess(endpoint: EndpointPreset) {
    tracker.recordSuccess(endpoint)
  }

  fun recordFailure(endpoint: EndpointPreset, reason: String) {
    tracker.recordFailure(endpoint, reason)
  }

  fun selectLeadingEndpoint(
    config: ConfigurationPreset?,
    endpoints: List<EndpointPreset>,
  ): ActiveEndpointSelection? {
    if (config == null) return null
    return tracker.selectLeadingEndpoint(config, endpoints)
  }

  fun orderedFailoverPlan(
    config: ConfigurationPreset?,
    endpoints: List<EndpointPreset>,
  ): List<Pair<EndpointSlot, EndpointPreset>> {
    if (config == null) return emptyList()
    return tracker.orderedFailoverPlan(config, endpoints)
  }

  suspend fun checkEndpointNow(
    endpoint: EndpointPreset,
    appPreset: AppSettingsPreset?,
    onEndpointDetected: (String, String, ServerFamily) -> Unit = { _, _, _ -> },
  ): HealthCheckResult {
    _checkingEndpointIds.update { it + endpoint.id }
    return try {
      val result =
        endpointClient.quickHealthCheck(
          settings = endpoint.toProbeSettings(appPreset),
          onEndpointDetected = { url, family ->
            onEndpointDetected(endpoint.cacheKey, url, family)
          },
        )
      when (result) {
        is HealthCheckResult.Healthy -> tracker.recordSuccess(endpoint)
        is HealthCheckResult.Unhealthy -> tracker.recordFailure(endpoint, result.reason)
      }
      result
    } finally {
      _checkingEndpointIds.update { it - endpoint.id }
    }
  }

  /**
   * Called concurrently the moment voice recording starts:
   * - If leading endpoint's 1-hour timer expired (or never checked), checks it immediately in background
   *   and falls through to Secondary/Tertiary if unreachable.
   * - If a backup (Secondary/Tertiary) is leading because Primary is DEGRADED, starts an independent
   *   background recovery check on Primary without blocking the backup path.
   *
   * Returns a [Job] ONLY when the currently leading Primary endpoint itself was stale (>1h) and is
   * actively being verified, so final transcription can optionally join it cleanly instead of hitting
   * a dead Primary twice.
   */
  fun onRecordingStarted(
    config: ConfigurationPreset,
    endpoints: List<EndpointPreset>,
    appPreset: AppSettingsPreset?,
  ): Job? {
    val usable = config.orderedUsableSlots(endpoints)
    if (usable.isEmpty()) return null
    val leading = tracker.selectLeadingEndpoint(config, endpoints) ?: return null

    val onDetected: (String, String, ServerFamily) -> Unit = { key, url, family ->
      settingsRepo.saveDetectedEndpointForPreset(leading.endpoint.id, key, url, family)
    }

    // If backup is leading because Primary is DEGRADED, probe Primary in detached background
    if (config.allowEndpointFailover && leading.isBackupActive) {
      val primary = usable.firstOrNull { it.first == EndpointSlot.PRIMARY }?.second
      if (primary != null && tracker.needsRecoveryCheck(primary)) {
        scope.launch {
          checkEndpointNow(primary, appPreset) { key, url, family ->
            settingsRepo.saveDetectedEndpointForPreset(primary.id, key, url, family)
          }
        }
      }
      // Check leading backup only if its own 1h timer expired
      if (tracker.needsFreshCheck(leading.endpoint)) {
        scope.launch {
          val res = checkEndpointNow(leading.endpoint, appPreset, onDetected)
          if (res is HealthCheckResult.Unhealthy) {
            val nextBackup =
              usable.firstOrNull { (slot, ep) ->
                slot != leading.slot && ep.id != primary?.id && tracker.needsFreshCheck(ep)
              }?.second
            if (nextBackup != null) {
              checkEndpointNow(nextBackup, appPreset) { key, url, family ->
                settingsRepo.saveDetectedEndpointForPreset(nextBackup.id, key, url, family)
              }
            }
          }
        }
      }
      return null
    }

    // Leading is Primary (or failover is disabled)
    if (!tracker.needsFreshCheck(leading.endpoint)) {
      return null
    }

    return scope.launch {
      val res = checkEndpointNow(leading.endpoint, appPreset, onDetected)
      if (res is HealthCheckResult.Unhealthy && config.allowEndpointFailover) {
        for ((slot, backup) in usable) {
          if (slot == EndpointSlot.PRIMARY) continue
          if (tracker.needsFreshCheck(backup)) {
            val backupRes =
              checkEndpointNow(backup, appPreset) { key, url, family ->
                settingsRepo.saveDetectedEndpointForPreset(backup.id, key, url, family)
              }
            if (backupRes is HealthCheckResult.Healthy) break
          } else if (tracker.recordFor(backup).status == EndpointHealthStatus.HEALTHY) {
            break
          }
        }
      }
    }
  }

  /**
   * After a backup endpoint was used for transcription (or live recording), verify Primary in the
   * background if it hasn't been checked within the debounce window, so Primary automatically
   * recovers once back online.
   */
  fun schedulePrimaryRecoveryIfUsingBackup(
    config: ConfigurationPreset?,
    endpoints: List<EndpointPreset>,
    appPreset: AppSettingsPreset?,
    usedSlot: EndpointSlot,
  ) {
    if (config == null || !config.allowEndpointFailover || usedSlot == EndpointSlot.PRIMARY) return
    val primary =
      config.primaryEndpointId?.let { id -> endpoints.firstOrNull { it.id == id && it.isUsable } }
        ?: return
    if (!tracker.needsRecoveryCheck(primary)) return

    scope.launch {
      checkEndpointNow(primary, appPreset) { key, url, family ->
        settingsRepo.saveDetectedEndpointForPreset(primary.id, key, url, family)
      }
    }
  }

  companion object {
    @Volatile private var instance: EndpointHealthManager? = null

    fun get(context: Context): EndpointHealthManager {
      return instance
        ?: synchronized(this) {
          instance ?: EndpointHealthManager(context.applicationContext).also { instance = it }
        }
    }
  }
}
