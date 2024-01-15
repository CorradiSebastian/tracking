package com.sebastiancorradi.track.services



import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sebastiancorradi.track.R
import com.sebastiancorradi.track.TrackApp
import com.sebastiancorradi.track.data.EventType
import com.sebastiancorradi.track.domain.CreateNotificationChannelUseCase
import com.sebastiancorradi.track.domain.CreateNotificationUseCase
import com.sebastiancorradi.track.domain.SaveLocationUseCase
import com.sebastiancorradi.track.domain.StartTrackingUseCase
import com.sebastiancorradi.track.domain.StopTrackingUseCase
import com.sebastiancorradi.track.store.UserStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * Service which manages turning location updates on and off. UI clients should bind to this service
 * to access this functionality.
 *
 * This service can be started the usual way (i.e. startService), but it will also start itself when
 * the first client binds to it. Thereafter it will manage its own lifetime as follows:
 *   - While there are any bound clients, the service remains started in the background. If it was
 *     in the foreground, it will exit the foreground, cancelling any ongoing notification.
 *   - When there are no bound clients and location updates are on, the service moves to the
 *     foreground and shows an ongoing notification with the latest location.
 *   - When there are no bound clients and location updates are off, the service stops itself.
 */
@AndroidEntryPoint
class ForegroundLocationService : LifecycleService() {

    @Inject
    lateinit var startTrackingUseCase: StartTrackingUseCase
    @Inject
    lateinit var stopTrackingUseCase: StopTrackingUseCase
    @Inject
    lateinit var saveLocationUseCase: SaveLocationUseCase
    @Inject
    lateinit var createNotificationUseCase: CreateNotificationUseCase
    @Inject
    lateinit var createNotificationChannelUseCase: CreateNotificationChannelUseCase

    private var _lastLocationFlow: MutableStateFlow<Location?>? = null

    private val deviceId by lazy{ (applicationContext as TrackApp).getDeviceID()}

    private val localBinder = LocalBinder()
    private var bindCount = 0

    private var started = false
    private var isForeground = false
    private lateinit var store : UserStore

    private fun isBound() = bindCount > 0

    override fun onCreate() {
        super.onCreate()

        store = UserStore(this)

        createNotificationChannelUseCase(this)

    }

    override fun stopService(name: Intent?): Boolean {
        return super.stopService(name)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.e("Sebastrack", "onStartCommand, action: ${intent?.getAction()}")
        if (ACTION_STOP_UPDATES.equals(intent?.getAction())) {
            stopSelf();
            Log.e("Sebastrack", "onStartCommand, stopped")
            //locationRepository.stopLocationUpdates()
            stopTrackingUseCase()
            saveLocationUseCase.invoke(null, deviceId, EventType.STOP)
            lifecycleScope.launch {
                store.saveTrackingStatus(false)
            }
            return START_NOT_STICKY
        }
        Log.e("Sebastrack", "onStartCommand, si ves esto al cerrar o detener... esta mal")
        //val notification = buildNotification(null)
        val notification = createNotificationUseCase(this)
        ServiceCompat.startForeground(
            /* service = */ this,
            /* id = */ 100, // Cannot be 0
            /* notification = */ notification,
            /* foregroundServiceType = */
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )


        // Startup tasks only happen once.
        if (!started) {

            started = true
            // Check if we should turn on location updates.
            lifecycleScope.launch {
                store.saveTrackingStatus(true)
                val flow = startTrackingUseCase(deviceId)
                updateLastLocationFlow(flow)
                saveLocationUseCase.invoke(Location(null), deviceId, EventType.START)
            }

        }

        return START_STICKY
    }

    private fun updateLastLocationFlow(flow: MutableStateFlow<Location?>?){
        //TODO ver qu eno se sobreescriba ni quede algun flow colgando en el eter cosmico
        _lastLocationFlow = flow
        lifecycleScope.launch {
        //viewModelScope.launch {
            // Trigger the flow and consume its elements using collect
            _lastLocationFlow?.collect { location ->
                // Update DB, add latest location
                Log.e("Sebastrack", "updating location from inside service, location: $location")
                location?.let {
                    saveLocationUseCase.invoke(it, deviceId, EventType.TRACK)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        handleBind()
        return localBinder
    }

    override fun onRebind(intent: Intent?) {
        handleBind()
    }

    private fun handleBind() {
        bindCount++
        // Start ourself. This will let us manage our lifetime separately from bound clients.
        startService(Intent(this, this::class.java))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bindCount--
        lifecycleScope.launch {
            // UI client can unbind because it went through a configuration change, in which case it
            // will be recreated and bind again shortly. Wait a few seconds, and if still not bound,
            // manage our lifetime accordingly.
            delay(UNBIND_DELAY_MILLIS)
            //manageLifetime()
        }
        // Allow clients to rebind, in which case onRebind will be called.
        return true
    }




    /** Binder which provides clients access to the service. */
    internal inner class LocalBinder : Binder() {
        fun getService(): ForegroundLocationService = this@ForegroundLocationService
    }

    companion object {
        const val UNBIND_DELAY_MILLIS = 2000.toLong() // 2 seconds
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "LocationUpdates"
        const val ACTION_STOP_UPDATES = ".ACTION_STOP_UPDATES"
    }

}

/**
 * ServiceConnection that provides access to a [ForegroundLocationService].
 */

class ForegroundLocationServiceConnection @Inject constructor() : ServiceConnection {

    var service: ForegroundLocationService? = null
        private set

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as ForegroundLocationService.LocalBinder).getService()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        // Note: this should never be called since the service is in the same process.
        service = null
    }
}
