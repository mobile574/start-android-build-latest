package tart.legacy

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.Choreographer
import tart.legacy.AppLifecycleState.PAUSED
import tart.legacy.AppLifecycleState.RESUMED
import tart.legacy.AppStart.AppStartData
import tart.internal.AppUpdateDetector.Companion.trackAppUpgrade
import tart.internal.MyProcess
import tart.internal.MyProcess.MyProcessData
import tart.internal.MyProcess.NoMyProcessData
import tart.internal.PerfsActivityLifecycleCallbacks.Companion.trackActivityLifecycle
import tart.internal.enforceMainThread
import tart.internal.isOnMainThread
import tart.legacy.AppStart.NoAppStartData

/**
 * Singleton object centralizing state for app start and future other perf metrics.
 */
object Perfs {

  private const val LAST_RESUMED_STATE = "lastResumedState"
  private const val LAST_RESUMED_CURRENT_MILLIS = "lastResumedCurrentMillis"
  private const val LAST_ALIVE_CURRENT_MILLIS = "lastAliveCurrentMillis"

  @Volatile
  private var initialized = false

  @Volatile
  private var notInitializedReason = "Perfs.init() was never called"

  @Volatile
  private lateinit var appStartData: AppStartData

  private val classInitUptimeMillis: Long = SystemClock.uptimeMillis()

  internal var classLoaderInstantiatedUptimeMillis: Long? = null
  internal var applicationInstantiatedUptimeMillis: Long? = null
  internal var firstPostApplicationComponentInstantiated = false

  private var reportedFullDrawn = false

  /**
   * Can be set to listen to app warm starts.
   */
  var appWarmStartListener: ((AppWarmStart) -> Unit)? = null

  internal fun firstClassLoaded() {
    // Prior to Android P, PerfsAppStartListener is the first loaded class
    // that we have control over. On Android P+, it's PerfAppComponentFactory.
    // They both call firstClassLoaded() when their class get loaded.
    // This method does nothing but forces a call to this constructor, and classInitUptimeMillis
    // gets set.
  }

  /**
   * Provides [AppStart] filled in with the latest information. Can be called from any thread.
   */
  val appStart: AppStart
    get() = if (initialized) {
      appStartData
    } else {
      NoAppStartData(notInitializedReason)
    }

  internal fun init(context: Context) {
    val initCalledUptimeMillis = SystemClock.uptimeMillis()
    // Should only be init on the main thread, once.
    if (!isOnMainThread() || initialized) {
      return
    }
    if (context !is Application) {
      notInitializedReason =
        "Perfs.init() called with a non Application context: ${context::class.java}"
      return
    }
    val myProcessInfo = when (val myProcessInfo = MyProcess.findMyProcessInfo(context)) {
      is NoMyProcessData -> {
        notInitializedReason = "Error retrieving process info: ${myProcessInfo.reason}"
        return
      }
      is MyProcessData -> {
        myProcessInfo
      }
    }
    initialized = true
    notInitializedReason = ""
    val application: Application = context

    val elapsedSinceProcessStartMillis =
      SystemClock.elapsedRealtime() - myProcessInfo.processStartRealtimeMillis
    // We rely on SystemClock.uptimeMillis() for performance related metrics.
    // See https://dev.to/pyricau/android-vitals-what-time-is-it-2oih
    val processStartUptimeMillis = SystemClock.uptimeMillis() - elapsedSinceProcessStartMillis

    // "handleBindApplication" because Process.setStartTimes is called from
    // ActivityThread.handleBindApplication
    val handleBindApplicationElapsedUptimeMillis = if (Build.VERSION.SDK_INT >= 24) {
      android.os.Process.getStartUptimeMillis() - processStartUptimeMillis
    } else {
      null
    }

    val handler = Handler(Looper.getMainLooper())
    var afterFirstPost = false
    handler.post {
      afterFirstPost = true
      val firstPost = appStartData.elapsedSinceStart()
      appStartData = appStartData.copy(firstPostElapsedUptimeMillis = firstPost)
    }
    val processInfoAfterFirstPost = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfoAfterFirstPost)

    // Some Android implementations perform a disk read when loading shared prefs async.
    val oldPolicy = StrictMode.allowThreadDiskReads()
    val prefs = try {
      application.getSharedPreferences("Perfs", Context.MODE_PRIVATE)
    } finally {
      StrictMode.setThreadPolicy(oldPolicy)
    }

    val lastAppLifecycleState = prefs.getString(LAST_RESUMED_STATE, null)
      ?.let { stateName -> if (stateName == RESUMED.name) RESUMED else PAUSED }
    val lastAppLifecycleStateChangedElapsedTimeMillis =
      prefs.getLong(LAST_RESUMED_CURRENT_MILLIS, -1).let { lastTime ->
        if (lastTime == -1L) {
          null
        } else {
          System.currentTimeMillis() - lastTime
        }
      }
    val lastAppAliveElapsedTimeMillis = prefs.getLong(LAST_ALIVE_CURRENT_MILLIS, -1).let { lastTime ->
      if (lastTime == -1L) {
        null
      } else {
        System.currentTimeMillis() - lastTime
      }
    }
    val processInfo = myProcessInfo.info
    appStartData = AppStartData(
      processStartRealtimeMillis = myProcessInfo.processStartRealtimeMillis,
      processStartUptimeMillis = processStartUptimeMillis,
      handleBindApplicationElapsedUptimeMillis = handleBindApplicationElapsedUptimeMillis,
      firstAppClassLoadElapsedUptimeMillis = classInitUptimeMillis - processStartUptimeMillis,
      perfsInitElapsedUptimeMillis = initCalledUptimeMillis - processStartUptimeMillis,
      importance = processInfo.importance,
      importanceAfterFirstPost = processInfoAfterFirstPost.importance,
      importanceReasonCode = processInfo.importanceReasonCode,
      importanceReasonPid = processInfo.importanceReasonPid,
      startImportanceReasonComponent = processInfo.importanceReasonComponent?.toShortString(),
      lastAppLifecycleState = lastAppLifecycleState,
      lastAppLifecycleStateChangedElapsedTimeMillis = lastAppLifecycleStateChangedElapsedTimeMillis,
      lastAppAliveElapsedTimeMillis = lastAppAliveElapsedTimeMillis,
      appTasks = myProcessInfo.appTasks,
      classLoaderInstantiatedElapsedUptimeMillis =
      classLoaderInstantiatedUptimeMillis?.let { it - processStartUptimeMillis },
      applicationInstantiatedElapsedUptimeMillis =
      applicationInstantiatedUptimeMillis?.let { it - processStartUptimeMillis }
    )

    object : Runnable {
      override fun run() {
        prefs.edit()
          // We can't use SystemClock.uptimeMillis() as the device might restart in between.
          .putLong(LAST_ALIVE_CURRENT_MILLIS, System.currentTimeMillis())
          .apply()
        handler.postDelayed(this, 1000)
      }
    }.apply { run() }

    Looper.myQueue()
      .addIdleHandler {
        val firstIdle = appStartData.elapsedSinceStart()
        appStartData = appStartData.copy(firstIdleElapsedUptimeMillis = firstIdle)
        false
      }

    var enteredBackgroundForWarmStartUptimeMillis = initCalledUptimeMillis

    application.trackActivityLifecycle(
      { updateAppStartData ->
        appStartData = updateAppStartData(appStartData)
      },
      { state, temperature ->
        // Note: we only start tracking app lifecycle state after the first resume. If the app has
        // never been resumed, the last state will stay null.
        prefs.edit()
          .putString(LAST_RESUMED_STATE, state.name)
          // We can't use SystemClock.uptimeMillis() as the device might restart in between.
          .putLong(LAST_RESUMED_CURRENT_MILLIS, System.currentTimeMillis())
          .apply()

        if (state == PAUSED) {
          enteredBackgroundForWarmStartUptimeMillis = SystemClock.uptimeMillis()
        } else {
          // A change of state before the first post indicates a cold start. This tracks warm and hot
          // starts.
          if (afterFirstPost) {
            val resumedUptimeMillis = SystemClock.uptimeMillis()
            val backgroundElapsedUptimeMillis = resumedUptimeMillis - enteredBackgroundForWarmStartUptimeMillis

            Choreographer.getInstance().postFrameCallback {
              handler.postAtFrontOfQueue {
                val resumeToNextFrameElapsedUptimeMillis =
                  SystemClock.uptimeMillis() - resumedUptimeMillis
                appWarmStartListener?.let { listener ->
                  listener(
                    AppWarmStart(
                      temperature = temperature,
                      backgroundElapsedUptimeMillis = backgroundElapsedUptimeMillis,
                      resumeToNextFrameElapsedUptimeMillis = resumeToNextFrameElapsedUptimeMillis
                    )
                  )
                }
              }
            }
          }
        }
      }
    )

    application.trackAppUpgrade { updateAppStartData ->
      appStartData = updateAppStartData(appStartData)
    }
    handler.postAtFrontOfQueue {
      val firstPostAtFront = appStartData.elapsedSinceStart()
      appStartData = appStartData.copy(firstPostAtFrontElapsedUptimeMillis = firstPostAtFront)
    }
  }

  internal fun firstComponentInstantiated(componentName: String) {
    enforceMainThread()
    if (!initialized) {
      return
    }
    appStartData = appStartData.copy(
      firstComponentInstantiated = ComponentInstantiatedEvent(
        componentName,
        appStartData.elapsedSinceStart()
      )
    )
  }

  /**
   * Functional equivalent to [android.app.Activity.reportFullyDrawn]. This lets app report when
   * they consider themselves ready, regardless of any prior view traversal and rendering.
   * The first call to [reportFullyDrawn] will update
   * [AppStartData.firstFrameAfterFullyDrawnElapsedUptimeMillis] on the next frame.
   */
  fun reportFullyDrawn() {
    enforceMainThread()
    if (!initialized || reportedFullDrawn) {
      return
    }
    reportedFullDrawn = true
    Choreographer.getInstance()
      .postFrameCallback {
        appStartData = appStartData.copy(
          firstFrameAfterFullyDrawnElapsedUptimeMillis = appStartData.elapsedSinceStart()
        )
      }
  }

  @JvmStatic
  @JvmOverloads
  fun customFirstEvent(
    eventName: String,
    extra: Any? = null
  ) {
    enforceMainThread()
    if (!initialized || eventName in appStartData.customFirstEvents) {
      return
    }
    val elapsedUptimeMillis = appStartData.elapsedSinceStart()
    appStartData = appStartData.copy(
      customFirstEvents = appStartData.customFirstEvents + mapOf(
        eventName to (elapsedUptimeMillis to extra)
      )
    )
  }
}
