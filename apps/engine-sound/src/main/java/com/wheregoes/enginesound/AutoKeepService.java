package com.wheregoes.enginesound;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.wheregoes.byd.Vehicle;

import java.lang.reflect.Method;

/**
 * Keeps the selected engine-sound preset while the simulator is already ON.
 *
 * Important: this service NEVER turns the simulator on. After a vehicle reboot,
 * if the MCU reports STATE=0, the service waits. The user must enable the
 * simulator manually in MainActivity.
 */
public class AutoKeepService extends Service {

    private static final String TAG = "EngineKeep";
    private static final String CHANNEL_ID = "engine_keep";
    private static final int NOTIFICATION_ID = 41;

    /** True while the control screen is visible. */
    static volatile boolean sUiActive = false;
    /** True while MainActivity is connecting/applying its startup sequence. */
    static volatile boolean sStartupInProgress = false;

    private static final int D = 1002;
    private static final int FID_STATE_GET    = 0x48F0000A;
    private static final int FID_SRC_TYPE_GET = 0x48F00010;
    private static final int FID_SRC_TYPE_SET = 0x3E300038;
    private static final int FID_HAS_SIM      = 0x48F00000;

    private static final long FAST_MS = 5000;
    private static final long SLOW_MS = 30000;
    private static final long SETTLE_MS = 150;
    private static final int MAX_FAST_FIXES = 5;

    private HandlerThread ioThread;
    private Handler io;
    private Object mgr;
    private Method setIntMethod;
    private Method getIntMethod;
    private int fixes = 0;

    private final Runnable tick = this::tickOnce;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.keeper_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION_ID, buildNotification());

        ioThread = new HandlerThread("engine-keep");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        // Give the vehicle service a moment to come up after boot.
        io.postDelayed(tick, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (io != null) {
            io.removeCallbacksAndMessages(null);
        }
        if (ioThread != null) {
            ioThread.quitSafely();
        }
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.keeper_running))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------------- loop

    private void tickOnce() {
        long next = FAST_MS;
        try {
            SharedPreferences prefs = getSharedPreferences(
                    EngineConfig.PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(EngineConfig.KEY_AUTO, false)) {
                stopSelf();
                return;
            }

            // Do not compete with the Activity's own serialized vehicle I/O.
            if (!sUiActive && !sStartupInProgress && connect()) {
                next = check(prefs.getInt(
                        EngineConfig.KEY_PRESET, EngineConfig.DEFAULT_PRESET));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
        }
        if (io != null) {
            io.postDelayed(tick, next);
        }
    }

    /** @return delay until the next check. */
    private int check(int target) {
        Integer state = getInt(FID_STATE_GET);
        if (state == null) {
            return (int) FAST_MS;
        }

        // Never enable the simulator from the background. Wait until the user
        // manually switches it on in MainActivity.
        if (state != 1) {
            fixes = 0;
            return (int) SLOW_MS;
        }

        Integer type = getInt(FID_SRC_TYPE_GET);
        if (type == null) {
            return (int) FAST_MS;
        }
        if (type == target) {
            fixes = 0;
            return (int) SLOW_MS;
        }

        // Stop hammering the MCU if it keeps refusing; try again at the slow pace.
        if (fixes >= MAX_FAST_FIXES) {
            fixes = 0;
            return (int) SLOW_MS;
        }

        fixes++;
        Log.i(TAG, "restoring preset: state=" + state
                + " type=" + type + " target=" + target);
        if (!setInt(FID_SRC_TYPE_SET, target)) {
            return (int) FAST_MS;
        }
        sleep(SETTLE_MS);

        Integer actual = getInt(FID_SRC_TYPE_GET);
        if (actual == null || actual != target) {
            Log.w(TAG, "preset readback mismatch: wanted=" + target
                    + " got=" + (actual == null ? "null" : actual));
        } else {
            fixes = 0;
            return (int) SLOW_MS;
        }
        return (int) FAST_MS;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ vehicle I/O

    private boolean connect() {
        if (mgr != null) {
            return true;
        }
        try {
            Object m = Vehicle.autoService(new BydPermissionContext(this));
            if (m == null) {
                return false;
            }
            Method set = m.getClass().getMethod(
                    "setInt", int.class, int.class, int.class);
            Method get = m.getClass().getMethod("getInt", int.class, int.class);
            Integer hasSim = (Integer) get.invoke(m, D, FID_HAS_SIM);
            if (hasSim == null || hasSim != 2) {
                return false;
            }
            mgr = m;
            setIntMethod = set;
            getIntMethod = get;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "vehicle service not ready", t);
            return false;
        }
    }

    private boolean setInt(int fid, int val) {
        try {
            setIntMethod.invoke(mgr, D, fid, val);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, String.format(
                    "setInt(0x%08X, %d) failed", fid, val), t);
            return false;
        }
    }

    private Integer getInt(int fid) {
        try {
            return (Integer) getIntMethod.invoke(mgr, D, fid);
        } catch (Throwable t) {
            Log.w(TAG, String.format("getInt(0x%08X) failed", fid), t);
            return null;
        }
    }
}
