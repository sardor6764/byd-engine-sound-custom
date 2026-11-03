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
 * Keeps the engine sound on and on the chosen preset.
 *
 * The MCU forgets the simulator state when the car is switched off, so this
 * service checks the vehicle and re-applies the same write sequence the app
 * uses when the user flips the switch. It stays out of the way while the app is
 * on screen, so browsing presets or cycling is never fought.
 */
public class AutoKeepService extends Service {

    private static final String TAG = "EngineKeep";
    private static final String CHANNEL_ID = "engine_keep";
    private static final int NOTIFICATION_ID = 41;

    /** Set by MainActivity while it is visible. */
    static volatile boolean sUiActive = false;

    private static final int D = 1002;
    private static final int FID_STATE_GET    = 0x48F0000A;
    private static final int FID_STATE_SET    = 0x3E300020;
    private static final int FID_SRC_TYPE_GET = 0x48F00010;
    private static final int FID_SRC_TYPE_SET = 0x3E300038;
    private static final int FID_HAS_SIM      = 0x48F00000;
    private static final int FID_PA_CONTROL   = 0xAA000148;
    private static final int FID_MCU_SPEAK    = 0xAA000142;
    private static final int FID_FM_SPEAK     = 0xAA00011A;
    private static final int FID_AVAS_CFG     = 0xAA000171;

    private static final int[] SIM_ENABLE_FIDS = {
            FID_PA_CONTROL, FID_MCU_SPEAK, FID_FM_SPEAK, FID_AVAS_CFG, FID_STATE_SET
    };

    private static final long FAST_MS = 5000;
    private static final long SLOW_MS = 30000;
    private static final long SETTLE_MS = 150;
    /** Mirrors MainActivity.primeAndApply: the engine stays silent on the very
     *  first source-type write right after enabling, so a throwaway value goes
     *  first, with a longer pause, before the real target is written. */
    private static final long WARMUP_SETTLE_MS = 2000;
    /** Confirmed by ear to actually produce sound -- see MainActivity.WAKE_PRESET. */
    private static final int WAKE_PRESET = 2;
    private static final int FALLBACK_WAKE_PRESET = 1;
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
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
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
            SharedPreferences prefs = getSharedPreferences(EngineConfig.PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(EngineConfig.KEY_AUTO, false)) {
                stopSelf();
                return;
            }
            if (!sUiActive && connect()) {
                next = check(prefs.getInt(EngineConfig.KEY_PRESET, EngineConfig.DEFAULT_PRESET));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
        }
        io.postDelayed(tick, next);
    }

    /** @return delay until the next check. */
    private long check(int target) {
        Integer state = getInt(FID_STATE_GET);
        Integer type = getInt(FID_SRC_TYPE_GET);
        if (state == null || type == null) {
            return FAST_MS;
        }
        if (state == 1 && type == target) {
            fixes = 0;
            return SLOW_MS;
        }
        // Stop hammering the MCU if it keeps refusing; try again at the slow pace.
        if (fixes >= MAX_FAST_FIXES) {
            fixes = 0;
            return SLOW_MS;
        }
        fixes++;
        Log.i(TAG, "re-applying: state=" + state + " type=" + type + " target=" + target);
        boolean wasOff = state != 1;
        if (wasOff) {
            for (int fid : SIM_ENABLE_FIDS) {
                setInt(fid, 1);
            }
        }
        if (wasOff) {
            // Freshly enabled (e.g. right after boot): start on a preset confirmed
            // to actually make sound (WAKE_PRESET), then move to the real target.
            // Preset 1 reads back fine but is silent on this car, so it cannot wake
            // anything by itself -- it has to be a preset that genuinely plays.
            int wake = (target == WAKE_PRESET) ? FALLBACK_WAKE_PRESET : WAKE_PRESET;
            setInt(FID_SRC_TYPE_SET, wake);
            sleep(WARMUP_SETTLE_MS);
        }
        setInt(FID_SRC_TYPE_SET, target);
        sleep(SETTLE_MS);
        return FAST_MS;
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
            Method set = m.getClass().getMethod("setInt", int.class, int.class, int.class);
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

    private void setInt(int fid, int val) {
        try {
            setIntMethod.invoke(mgr, D, fid, val);
        } catch (Throwable t) {
            Log.w(TAG, String.format("setInt(0x%08X, %d) failed", fid, val), t);
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
