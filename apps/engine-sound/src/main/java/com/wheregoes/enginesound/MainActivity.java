package com.wheregoes.enginesound;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.wheregoes.byd.AutoStart;
import com.wheregoes.byd.Vehicle;

import java.lang.reflect.Method;

/**
 * Engine Voice Simulator control.
 *
 * All vehicle I/O runs on a single {@link HandlerThread}. That is load bearing:
 * the previous version spawned a thread per tap, and each thread wrote its own
 * readback into the label, so with several taps in flight the label showed
 * whichever thread finished last rather than the newest value. Serialising the
 * I/O makes out-of-order readbacks impossible.
 */
public class MainActivity extends Activity {

    private static final String TAG = "EngineSound";

    private static final String PREFS = EngineConfig.PREFS;
    private static final String KEY_NAME_PREFIX = "preset_name_";

    private static final int D = 1002;

    private static final int FID_STATE_GET    = 0x48F0000A;
    private static final int FID_STATE_SET    = 0x3E300020;
    private static final int FID_SRC_TYPE_GET = 0x48F00010;
    private static final int FID_SRC_TYPE_SET = 0x3E300038;
    private static final int FID_HAS_SIM      = 0x48F00000;
    private static final int FID_HAS_SRC      = 0x48F00013;
    private static final int FID_PA_CONTROL   = 0xAA000148;
    private static final int FID_MCU_SPEAK    = 0xAA000142;
    private static final int FID_FM_SPEAK     = 0xAA00011A;
    private static final int FID_AVAS_CFG     = 0xAA000171;

    /** Feature ids written, in order, to bring the simulator up. */
    private static final int[] SIM_ENABLE_FIDS = {
            FID_PA_CONTROL, FID_MCU_SPEAK, FID_FM_SPEAK, FID_AVAS_CFG, FID_STATE_SET
    };

    /**
     * How many presets to offer.
     *
     * This is deliberately a fixed number, not a probed one. The MCU stores and
     * echoes back *any* SRC_TYPE we write -- measured to 200 on a Dolphin, with
     * only 0 rejected -- so a set/read-back probe cannot discover a bound; it
     * just walks to whatever ceiling the probe uses. No register reports the
     * count either: 0x48F00013 returns 1, meaning "a voice source exists".
     *
     * So the real number of distinct sounds is only knowable by ear, which is
     * what the long-press naming is for. 20 is a generous browsing range around
     * the 10 that live testing confirmed.
     */
    private static final int PRESET_RANGE = 20;
    /** Taps inside this window collapse into a single CAN write. */
    private static final long APPLY_DELAY_MS = 150;
    private static final long CYCLE_DWELL_MS = 2500;
    /** Pause between a write and its readback; the MCU does not answer instantly. */
    private static final long SETTLE_MS = 120;
    /** Pause after the enable sequence, before the first source-type write lands. */
    private static final long WARMUP_SETTLE_MS = 2000;
    /** Confirmed by ear to actually produce sound on this car -- used to wake the
     *  audio path before switching to whatever the user really wants. */
    private static final int WAKE_PRESET = 2;
    /** Used only on the rare case the user's own target preset IS the wake preset. */
    private static final int FALLBACK_WAKE_PRESET = 1;
    /** Preset to restore once when the app itself starts. */
    private static final int STARTUP_PRESET = 4;

    private Object mgr;
    private Method setIntMethod;
    private Method getIntMethod;

    private TextView typeLabel;
    private TextView presetName;
    private TextView statusLabel;
    private TextView simCaption;
    private View statusDot;
    private Switch simToggle;
    private Button prevBtn;
    private Button nextBtn;
    private Button cycleBtn;
    private LinearLayout chipsRow;
    private TextView vehiclePanel;

    private SharedPreferences prefs;
    private HandlerThread ioThread;
    private Handler io;

    /** Written on the main thread, read on the io thread. */
    private volatile int pendingType = 1;
    /**
     * False right after the simulator is switched on. The audio path is not awake
     * yet at that instant, so the first source-type write this power-on -- whichever
     * code path it comes from (the enable sequence, a preset chip, NEXT/PREV, or
     * Cycle) -- is primed instead of written plain. Cleared again on disableSim().
     */
    private volatile boolean audioWarmedUp = false;
    private volatile boolean cycling = false;
    private volatile boolean ready = false;
    private boolean simOn = false;
    private int cycleIndex = 1;
    /** Last vehicle write and what the readback said; the panel's fifth line. */
    private volatile String lastWriteLine = "no write yet";

    private final CompoundButton.OnCheckedChangeListener simListener =
            (btn, checked) -> {
                if (checked) {
                    enableSim();
                } else {
                    disableSim();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // This build is configured to start on preset 4. The MCU needs preset 2
        // first to wake the audio path, so initVehicle() applies 2 -> 4 once
        // when the app starts and the simulator is already enabled.
        pendingType = STARTUP_PRESET;
        prefs.edit().putInt(EngineConfig.KEY_PRESET, STARTUP_PRESET).apply();
        if (prefs.getBoolean(EngineConfig.KEY_AUTO, false)) {
            startForegroundService(new Intent(this, AutoKeepService.class));
        }

        typeLabel = findViewById(R.id.type_label);
        presetName = findViewById(R.id.preset_name);
        statusLabel = findViewById(R.id.status_label);
        simCaption = findViewById(R.id.sim_caption);
        statusDot = findViewById(R.id.status_dot);
        simToggle = findViewById(R.id.sim_toggle);
        prevBtn = findViewById(R.id.prev_btn);
        nextBtn = findViewById(R.id.next_btn);
        cycleBtn = findViewById(R.id.cycle_btn);
        chipsRow = findViewById(R.id.chips_row);
        vehiclePanel = findViewById(R.id.vehicle_panel);
        // Tapping the values re-reads them, so the panel needs no button of its own.
        vehiclePanel.setOnClickListener(v -> io.post(this::refreshVehiclePanel));

        prevBtn.setOnClickListener(v -> setPending(pendingType - 1));
        nextBtn.setOnClickListener(v -> setPending(pendingType + 1));
        cycleBtn.setOnClickListener(v -> toggleCycle());
        simToggle.setOnCheckedChangeListener(simListener);

        buildChips(PRESET_RANGE);
        setControlsEnabled(false);

        ioThread = new HandlerThread("avas-io");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        io.post(this::initVehicle);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        AutoKeepService.sUiActive = true;
        // First launch: initVehicle() does its own refresh once it is connected.
        if (ready) {
            io.post(this::readVehicleState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        AutoKeepService.sUiActive = false;
        stopCycle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ioThread != null) {
            ioThread.quitSafely();
        }
    }

    // ------------------------------------------------------------- vehicle init

    /** Runs on the io thread. */
    private void initVehicle() {
        BydPermissionContext ctx = new BydPermissionContext(this);
        try {
            mgr = Vehicle.autoService(ctx);
            if (mgr == null) {
                fail(getString(R.string.err_service_null));
                refreshVehiclePanel();
                return;
            }
            setIntMethod = mgr.getClass().getMethod("setInt", int.class, int.class, int.class);
            getIntMethod = mgr.getClass().getMethod("getInt", int.class, int.class);
        } catch (Throwable t) {
            Log.e(TAG, "auto service unavailable", t);
            fail(getString(R.string.err_service, String.valueOf(t.getMessage())));
            refreshVehiclePanel();
            return;
        }

        Integer hasSim = getInt(FID_HAS_SIM);
        Integer hasSrc = getInt(FID_HAS_SRC);
        // 0x48F00013 reports *whether* a source exists, not how many. It cannot
        // supply the preset count, which is why the range has to be probed.
        Log.i(TAG, "FID_HAS_SIM=" + hasSim + " FID_HAS_SRC=" + hasSrc);

        if (hasSim == null) {
            fail(getString(R.string.sim_unreadable));
            refreshVehiclePanel();
            return;
        }
        if (hasSim != 2) {
            fail(getString(R.string.sim_unsupported, hasSim));
            refreshVehiclePanel();
            return;
        }

        runOnUiThread(() -> setControlsEnabled(true));
        ready = true;
        applyStartupPreset();
    }

    /**
     * Runs once per Activity process start, not on every simulator toggle.
     * If the simulator is already on, explicitly wake the audio path with preset
     * 2 and then apply preset 4. If it is off, leave it off; the normal enable
     * path will perform the same 2 -> 4 sequence when the user turns it on.
     */
    private void applyStartupPreset() {
        pendingType = STARTUP_PRESET;
        Integer state = getInt(FID_STATE_GET);
        if (state != null && state == 1) {
            boolean writeOk = primeAndApply(STARTUP_PRESET);
            audioWarmedUp = writeOk;
            settle();
            Integer back = getInt(FID_SRC_TYPE_GET);
            Integer stateAfter = getInt(FID_STATE_GET);
            lastWriteLine = "startup 2 -> 4 -> read "
                    + (back == null ? "read failed" : back);
            if (back != null && back == STARTUP_PRESET) {
                prefs.edit().putInt(EngineConfig.KEY_PRESET, STARTUP_PRESET).apply();
            }
            final Integer actualType = back;
            final boolean simStillOn = stateAfter != null && stateAfter == 1;
            runOnUiThread(() -> {
                if (actualType != null && actualType > 0) {
                    pendingType = clamp(actualType);
                }
                renderPreset(pendingType);
                syncToggle(simStillOn);
                if (!writeOk || actualType == null || actualType != STARTUP_PRESET) {
                    setStatus(getString(R.string.preset_rejected, STARTUP_PRESET,
                            actualType == null ? 0 : actualType), R.color.warning);
                } else if (!simStillOn) {
                    setStatus(getString(R.string.sim_dropped, STARTUP_PRESET), R.color.warning);
                } else {
                    setStatus(getString(R.string.sim_on, STARTUP_PRESET), R.color.success);
                }
            });
            refreshVehiclePanel();
        } else {
            // Do not turn the simulator on just because the app was opened.
            audioWarmedUp = false;
            readVehicleState();
        }
    }

    /** The MCU needs a moment before a readback reflects the write. */
    private void settle() {
        try {
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs on the io thread. */
    private void readVehicleState() {
        Integer state = getInt(FID_STATE_GET);
        Integer src = getInt(FID_SRC_TYPE_GET);
        runOnUiThread(() -> {
            if (src != null && src > 0 && state != null && state == 1) {
                pendingType = clamp(src);
            }
            renderPreset(pendingType);
            syncToggle(state != null && state == 1);
            if (state == null) {
                setStatus(getString(R.string.read_failed), R.color.warning);
            } else {
                setStatus(getString(simOn ? R.string.sim_on : R.string.sim_off, pendingType),
                        simOn ? R.color.success : R.color.fg_secondary);
            }
        });
        refreshVehiclePanel();
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            setStatus(message, R.color.danger);
            setControlsEnabled(false);
            simToggle.setEnabled(false);
        });
    }

    // ------------------------------------------------------------ preset select

    private int clamp(int type) {
        return Math.max(1, Math.min(type, PRESET_RANGE));
    }

    /** Main thread. Updates the UI immediately, then coalesces the CAN write. */
    private void setPending(int type) {
        int want = clamp(type);
        pendingType = want;
        renderPreset(want);
        io.removeCallbacks(applyPending);
        io.postDelayed(applyPending, APPLY_DELAY_MS);
    }

    /**
     * Runs on the io thread: one write per burst of taps, always verified.
     *
     * A method reference, not an anonymous class: the pinned d8 8.2.2-dev
     * crashes dexing the null inner_name of an anonymous InnerClasses entry,
     * and every other app in this repo uses lambdas for the same reason.
     */
    private final Runnable applyPending = this::applyPendingNow;

    private void applyPendingNow() {
        final int want = pendingType;
        final boolean write = audioWarmedUp
                ? setInt(FID_SRC_TYPE_SET, want)
                : primeAndApply(want);
        if (!write) {
            runOnUiThread(() ->
                    setStatus(getString(R.string.write_failed, want), R.color.danger));
            refreshVehiclePanel();
            return;
        }
        audioWarmedUp = true;
        settle();
        Integer back = getInt(FID_SRC_TYPE_GET);
        lastWriteLine = "write 0x3E300038 = " + want + " -> read "
                + (back == null ? "read failed" : back);
        if (back == null) {
            runOnUiThread(() ->
                    setStatus(getString(R.string.read_failed), R.color.warning));
            refreshVehiclePanel();
            return;
        }
        final int got = back;
        if (got == want) {
            prefs.edit().putInt(EngineConfig.KEY_PRESET, got).apply();
        }
        // A car that stores the preset and then drops STATE back to 0 is what
        // "plays louder for a moment, then goes back to normal" looks like. The
        // status used to be rendered from the cached simOn flag, so the UI kept
        // showing a green sound ON that the vehicle had already abandoned.
        final Integer state = getInt(FID_STATE_GET);
        runOnUiThread(() -> {
            if (got != want) {
                // setInt returns success even for values the MCU ignores, so
                // the readback is the only truth. Show what the car actually did.
                pendingType = got;
                renderPreset(got);
                setStatus(getString(R.string.preset_rejected, want, got), R.color.warning);
            } else if (simOn && state != null && state != 1) {
                syncToggle(false);
                setStatus(getString(R.string.sim_dropped, got), R.color.warning);
            } else {
                setStatus(getString(simOn ? R.string.sim_on : R.string.sim_off, got),
                        simOn ? R.color.success : R.color.fg_secondary);
            }
        });
        refreshVehiclePanel();
    }

    // -------------------------------------------------------------- simulator

    private void enableSim() {
        io.post(() -> {
            for (int fid : SIM_ENABLE_FIDS) {
                if (!setInt(fid, 1)) {
                    final String hex = String.format("0x%08X", fid);
                    runOnUiThread(() -> {
                        setStatus(getString(R.string.sim_write_failed, hex), R.color.danger);
                        syncToggle(false);
                    });
                    refreshVehiclePanel();
                    return;
                }
            }
            // The audio engine is not awake the instant STATE_SET lands: a source-type
            // write right here is stored by the MCU but does not reach the speaker (see
            // primeAndApply). Warming up on a throwaway value first, then writing the
            // real target, is what actually produces sound on the first try.
            boolean presetOk = primeAndApply(pendingType);
            audioWarmedUp = presetOk;
            Integer actualType = getInt(FID_SRC_TYPE_GET);
            Integer state = getInt(FID_STATE_GET);
            lastWriteLine = "startup wake " + WAKE_PRESET + " -> target " + pendingType
                    + " -> read " + (actualType == null ? "read failed" : actualType)
                    + " | STATE " + (state == null ? "read failed" : state);
            final boolean on = state != null && state == 1;
            final boolean presetVerified = presetOk && actualType != null
                    && actualType == pendingType;
            runOnUiThread(() -> {
                syncToggle(on);
                if (!on) {
                    setStatus(getString(R.string.sim_verify_failed), R.color.danger);
                } else if (!presetVerified) {
                    setStatus(getString(R.string.preset_rejected, pendingType,
                            actualType == null ? 0 : actualType), R.color.warning);
                    armAutoKeep();
                } else {
                    setStatus(getString(R.string.sim_on, pendingType), R.color.success);
                    armAutoKeep();
                }
            });
            refreshVehiclePanel();
        });
    }

    /**
     * Runs on the io thread. Writes {@code target} so it is actually audible, not
     * just stored.
     *
     * Right after the enable sequence, the first SOURCE_TYPE_SET write is accepted
     * and echoed back by the MCU but does not come out of the speaker -- confirmed
     * on a Dolphin: picking preset 4 straight after turning the sound on saved 4 but
     * stayed silent, and only played once the app had been pointed at another preset
     * first. A longer settle alone was not enough; it took an actual value change.
     * So this always starts on a preset confirmed to actually make sound, then
     * switches to the real target after a real pause -- same as doing it by hand.
     *
     * Confirmed on this car: preset 1 writes and reads back fine but produces no
     * sound by itself, so writing it does not wake anything -- it has to be a
     * preset that genuinely plays, which here is {@link #WAKE_PRESET}. Starting
     * there and then moving to the target is what worked by hand; no extra
     * throwaway write of the target beforehand is needed.
     *
     * @return true only when the final target value is confirmed by MCU readback.
     *         This confirms the register value, not audible playback.
     */
    private boolean primeAndApply(int target) {
        int wake = (target == WAKE_PRESET) ? FALLBACK_WAKE_PRESET : WAKE_PRESET;

        // The wake write must be sent successfully before the target write. A
        // successful setInt call only means the HAL call did not throw; it does
        // not prove that the MCU stored the value, so read it back after settling.
        if (!setInt(FID_SRC_TYPE_SET, wake)) {
            Log.e(TAG, "wake preset write failed: " + wake);
            return false;
        }
        runOnUiThread(() -> setStatus(getString(R.string.sim_waking, wake), R.color.fg_secondary));
        sleepMs(WARMUP_SETTLE_MS);

        Integer wakeBack = getInt(FID_SRC_TYPE_GET);
        if (wakeBack == null || wakeBack != wake) {
            // Do not silently assume the priming write worked. Still send the
            // requested target after the full warm-up delay: the target readback
            // below decides whether the operation can be reported as successful.
            Log.w(TAG, "wake preset readback mismatch: wanted=" + wake
                    + " got=" + (wakeBack == null ? "null" : wakeBack));
        }

        if (!setInt(FID_SRC_TYPE_SET, target)) {
            Log.e(TAG, "target preset write failed: " + target);
            return false;
        }
        settle();
        Integer targetBack = getInt(FID_SRC_TYPE_GET);
        boolean verified = targetBack != null && targetBack == target;
        if (!verified) {
            Log.w(TAG, "target preset readback mismatch: wanted=" + target
                    + " got=" + (targetBack == null ? "null" : targetBack));
        }
        return verified;
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void disableSim() {
        stopCycle();
        audioWarmedUp = false;
        prefs.edit().putBoolean(EngineConfig.KEY_AUTO, false).apply();
        stopService(new Intent(this, AutoKeepService.class));
        io.post(() -> {
            setInt(FID_STATE_SET, 0);
            // The MCU does not answer instantly; without this the read was stale
            // and a successful switch-off could report "did not confirm".
            settle();
            Integer state = getInt(FID_STATE_GET);
            lastWriteLine = "write 0x3E300020 = 0 -> STATE "
                    + (state == null ? "read failed" : state);
            final boolean on = state != null && state == 1;
            runOnUiThread(() -> {
                syncToggle(on);
                setStatus(on ? getString(R.string.sim_verify_failed)
                             : getString(R.string.sim_turning_off),
                        on ? R.color.danger : R.color.fg_secondary);
            });
            refreshVehiclePanel();
        });
    }

    // ---------------------------------------------------------------- auto keep

    /** Main thread: remember the choice and start the keeper that restores it after a restart. */
    private void armAutoKeep() {
        prefs.edit()
                .putBoolean(EngineConfig.KEY_AUTO, true)
                .putInt(EngineConfig.KEY_PRESET, pendingType)
                .apply();
        startForegroundService(new Intent(this, AutoKeepService.class));
        if (AutoStart.shouldRemind(this, prefs)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.autostart_title)
                    .setMessage(R.string.autostart_msg)
                    .setPositiveButton(R.string.autostart_open, (d, w) -> {
                        if (AutoStart.open(this)) {
                            AutoStart.markReminded(this, prefs);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ cycling

    private void toggleCycle() {
        if (cycling) {
            stopCycle();
            setStatus(getString(R.string.cycle_stopped, pendingType), R.color.fg_secondary);
            return;
        }
        // The old version silently flipped the toggle on here, which raced the
        // cycle loop for FID_SRC_TYPE_SET. Ask instead.
        if (!simOn) {
            setStatus(getString(R.string.cycle_needs_sim), R.color.warning);
            return;
        }
        cycling = true;
        cycleIndex = 1;
        cycleBtn.setText(R.string.cycle_stop);
        io.post(cycleStep);
    }

    private void stopCycle() {
        cycling = false;
        if (io != null) {
            io.removeCallbacks(cycleStep);
        }
        cycleBtn.setText(R.string.cycle_all);
    }

    /**
     * One preset per pass, rescheduled rather than sleeping, so the io thread
     * stays free for taps while a cycle is running.
     */
    private final Runnable cycleStep = this::cycleOnce;

    private void cycleOnce() {
        if (!cycling) {
            return;
        }
        final int t = cycleIndex;
        if (t > PRESET_RANGE) {
            runOnUiThread(() -> {
                stopCycle();
                setStatus(getString(R.string.cycle_stopped, PRESET_RANGE),
                        R.color.fg_secondary);
            });
            return;
        }
        boolean ok = audioWarmedUp ? setInt(FID_SRC_TYPE_SET, t) : primeAndApply(t);
        audioWarmedUp = true;
        settle();
        final Integer back = getInt(FID_SRC_TYPE_GET);
        if (!ok || back == null || back != t) {
            runOnUiThread(() -> {
                stopCycle();
                setStatus(getString(R.string.preset_rejected, t,
                        back == null ? t : back), R.color.warning);
            });
            return;
        }
        pendingType = t;
        runOnUiThread(() -> {
            renderPreset(t);
            setStatus(getString(R.string.cycling_now, t), R.color.info);
        });
        cycleIndex = t + 1;
        io.postDelayed(cycleStep, CYCLE_DWELL_MS);
    }

    // ----------------------------------------------------------------- chips/UI

    private void buildChips(int max) {
        chipsRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int t = 1; t <= max; t++) {
            final int type = t;
            TextView chip = (TextView) inflater.inflate(R.layout.chip_preset, chipsRow, false);
            chip.setText(String.valueOf(type));
            chip.setOnClickListener(v -> setPending(type));
            chip.setOnLongClickListener(v -> {
                promptRename(type);
                return true;
            });
            chipsRow.addView(chip);
        }
        highlightChip(pendingType);
    }

    private void highlightChip(int type) {
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView chip = (TextView) chipsRow.getChildAt(i);
            boolean active = (i + 1) == type;
            chip.setBackgroundResource(
                    active ? R.drawable.glass_segmented_active : R.drawable.glass_pill);
            chip.setTextColor(getColor(active ? R.color.mint_700 : R.color.fg_primary));
        }
    }

    private void renderPreset(int type) {
        typeLabel.setText(String.valueOf(type));
        presetName.setText(presetLabel(type));
        highlightChip(type);
    }

    /**
     * BYD publishes no names for these presets -- they are opaque MCU flash
     * sounds, identifiable only by ear. So the owner names them, and the label
     * falls back to the number until they do.
     */
    private String presetLabel(int type) {
        String saved = prefs.getString(KEY_NAME_PREFIX + type, null);
        if (saved != null && !saved.trim().isEmpty()) {
            return saved;
        }
        return getString(R.string.preset_fallback, type);
    }

    private void promptRename(int type) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        String current = prefs.getString(KEY_NAME_PREFIX + type, "");
        input.setText(current);
        input.setSelection(current.length());

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.rename_title, type))
                .setView(input)
                .setPositiveButton(R.string.rename_save, (d, w) -> {
                    prefs.edit().putString(KEY_NAME_PREFIX + type,
                            input.getText().toString().trim()).apply();
                    renderPreset(pendingType);
                })
                .setNeutralButton(R.string.rename_reset, (d, w) -> {
                    prefs.edit().remove(KEY_NAME_PREFIX + type).apply();
                    renderPreset(pendingType);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setStatus(String text, int colorRes) {
        statusLabel.setText(text);
        statusLabel.setTextColor(getColor(colorRes));
        statusDot.getBackground().setTint(getColor(colorRes));
    }

    /** Single place the toggle is driven, so the listener cannot re-enter. */
    private void syncToggle(boolean on) {
        simOn = on;
        simToggle.setOnCheckedChangeListener(null);
        simToggle.setChecked(on);
        simToggle.setTrackResource(on
                ? R.drawable.glass_switch_track_on
                : R.drawable.glass_switch_track_off);
        simToggle.setOnCheckedChangeListener(simListener);
        simCaption.setTextColor(getColor(on ? R.color.mint_700 : R.color.fg_secondary));
    }

    private void setControlsEnabled(boolean enabled) {
        prevBtn.setEnabled(enabled);
        nextBtn.setEnabled(enabled);
        cycleBtn.setEnabled(enabled);
        float alpha = enabled ? 1f : 0.4f;
        prevBtn.setAlpha(alpha);
        nextBtn.setAlpha(alpha);
        cycleBtn.setAlpha(alpha);
    }

    /**
     * io thread. Five monospace lines, so one photo of the screen answers "what
     * does my car actually report" for a reporter with no PC and no adb.
     */
    private void refreshVehiclePanel() {
        final String text = "SIM   0x48F00000 = " + fmt(getInt(FID_HAS_SIM))
                + "\nSRC   0x48F00013 = " + fmt(getInt(FID_HAS_SRC))
                + "\nSTATE 0x48F0000A = " + fmt(getInt(FID_STATE_GET))
                + "\nTYPE  0x48F00010 = " + fmt(getInt(FID_SRC_TYPE_GET))
                + "\n" + lastWriteLine;
        runOnUiThread(() -> vehiclePanel.setText(text));
    }

    private static String fmt(Integer value) {
        return value == null ? "read failed" : String.valueOf(value);
    }

    // ------------------------------------------------------------- vehicle I/O

    /** @return false if the write threw; a true return does NOT mean accepted. */
    private boolean setInt(int fid, int val) {
        try {
            setIntMethod.invoke(mgr, D, fid, val);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, String.format("setInt(0x%08X, %d) failed", fid, val), t);
            return false;
        }
    }

    /** @return null when the read failed, so callers cannot mistake it for data. */
    private Integer getInt(int fid) {
        try {
            return (Integer) getIntMethod.invoke(mgr, D, fid);
        } catch (Throwable t) {
            Log.w(TAG, String.format("getInt(0x%08X) failed", fid), t);
            return null;
        }
    }
}
