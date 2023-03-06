package com.baseflow.geolocator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.baseflow.geolocator.errors.ErrorCodes;
import com.baseflow.geolocator.location.BackgroundNotification;
import com.baseflow.geolocator.location.ForegroundNotificationOptions;
import com.baseflow.geolocator.location.GeolocationManager;
import com.baseflow.geolocator.location.LocationClient;
import com.baseflow.geolocator.location.LocationMapper;
import com.baseflow.geolocator.location.LocationOptions;

import io.flutter.plugin.common.EventChannel;

public class GeolocatorLocationService extends Service {
    private static final String TAG = "FlutterGeolocator";
    private static final int FALLBACK_NOTIFICATION_ID = 75415;
    private static final String CHANNEL_ID = "geolocator_channel_01";
    private final String WAKELOCK_TAG = "GeolocatorLocationService:Wakelock";
    private final String WIFILOCK_TAG = "GeolocatorLocationService:WifiLock";
    private final LocalBinder binder = new LocalBinder(this);
    // Service is foreground
    private boolean isForeground = false;
    private int connectedEngines = 0;
    private int listenerCount = 0;
    @Nullable
    private Activity activity = null;
    @Nullable
    private GeolocationManager geolocationManager = null;
    @Nullable
    private LocationClient locationClient;

    @Nullable
    private PowerManager.WakeLock wakeLock = null;
    @Nullable
    private WifiManager.WifiLock wifiLock = null;

    @Nullable
    private BackgroundNotification backgroundNotification = null;

    @Nullable
    private ForegroundNotificationOptions foregroundNotificationOptions;

    @Nullable
    private LogListener logListener;

    @Override
    public void onCreate() {
        super.onCreate();
        log("Creating service.");
        geolocationManager = new GeolocationManager(logListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        log("Binding to location service.");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        log("Unbinding from location service.");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        log("Destroying location service.");

        stopLocationService();
        disableBackgroundMode();
        geolocationManager = null;
        backgroundNotification = null;

        log("Destroyed location service.");
        super.onDestroy();
    }

    public boolean canStopLocationService(boolean cancellationRequested) {
        if (cancellationRequested) {
            return listenerCount == 1;
        }
        return connectedEngines == 0;
    }

    public void flutterEngineConnected() {

        connectedEngines++;
        log("Flutter engine connected. Connected engine count " + connectedEngines);
    }

    public void flutterEngineDisconnected() {

        connectedEngines--;
        log("Flutter engine disconnected. Connected engine count " + connectedEngines);
    }

    public void startLocationService(
            boolean forceLocationManager,
            LocationOptions locationOptions,
            EventChannel.EventSink events,
            LogListener logListener) {

        this.logListener = logListener;
        listenerCount++;
        if (geolocationManager != null) {
            locationClient =
                    geolocationManager.createLocationClient(
                            this.getApplicationContext(),
                            Boolean.TRUE.equals(forceLocationManager),
                            locationOptions);

            geolocationManager.startPositionUpdates(
                    locationClient,
                    activity,
                    (Location location) -> events.success(LocationMapper.toHashMap(location)),
                    (ErrorCodes errorCodes) ->
                            events.error(errorCodes.toString(), errorCodes.toDescription(), null));
        }
    }

    public void stopLocationService() {
        listenerCount--;
        log("Stopping location service.");
        if (locationClient != null && geolocationManager != null) {
            geolocationManager.stopPositionUpdates(locationClient);
        }
    }

    public void enableBackgroundMode(ForegroundNotificationOptions options) {
        foregroundNotificationOptions = options;

        if (backgroundNotification != null) {
            log("Service already in foreground mode.");
            changeNotificationOptions(options);
        } else {
            log("Start service in foreground mode. Existing notificationId: " + options.getExistingNotificationId());

            int notificationId = getNotificationId();

            backgroundNotification = new BackgroundNotification(this.getApplicationContext(), CHANNEL_ID, notificationId, options);
            backgroundNotification.updateChannel("Background Location");

            Notification notification = backgroundNotification.build();
            startForeground(notificationId, notification);

            isForeground = true;
        }
        obtainWakeLocks(options);
    }

    @SuppressWarnings("deprecation")
    public void disableBackgroundMode() {
        if (isForeground) {
            log("Stop service in foreground.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(getNotificationId());
            } else {
                stopForeground(true);
            }
            releaseWakeLocks();
            isForeground = false;
            backgroundNotification = null;
        }
    }

    public void changeNotificationOptions(ForegroundNotificationOptions options) {
        if (backgroundNotification != null) {
            backgroundNotification.updateOptions(options, isForeground);
            obtainWakeLocks(options);
        }
    }

    public void setActivity(@Nullable Activity activity) {
        this.activity = activity;
    }

    private void releaseWakeLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    @SuppressLint("WakelockTimeout")
    private void obtainWakeLocks(ForegroundNotificationOptions options) {
        releaseWakeLocks();
        if (options.isEnableWakeLock()) {
            PowerManager powerManager =
                    (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        if (options.isEnableWifiLock()) {
            WifiManager wifiManager =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFILOCK_TAG);
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    private int getNotificationId() {
        if (foregroundNotificationOptions != null && foregroundNotificationOptions.getExistingNotificationId() != null) {
            return foregroundNotificationOptions.getExistingNotificationId();
        }
        return FALLBACK_NOTIFICATION_ID;
    }

    class LocalBinder extends Binder {
        private final GeolocatorLocationService locationService;

        LocalBinder(GeolocatorLocationService locationService) {
            this.locationService = locationService;
        }

        public GeolocatorLocationService getLocationService() {
            return locationService;
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        if(logListener == null) return;

        logListener.onLog(TAG, message);
    }
}
