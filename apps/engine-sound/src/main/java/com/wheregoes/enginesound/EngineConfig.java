package com.wheregoes.enginesound;

/** Settings shared by the activity, the boot receiver and the keeper service. */
final class EngineConfig {
    private EngineConfig() {}

    static final String PREFS = "engine_sound_prefs";
    /** True once the user has switched the sound ON in the app; the keeper only runs then. */
    static final String KEY_AUTO = "auto_enabled";
    /** Preset the keeper restores. Follows whatever the user last picked. */
    static final String KEY_PRESET = "auto_preset";
    static final int DEFAULT_PRESET = 3;
}
