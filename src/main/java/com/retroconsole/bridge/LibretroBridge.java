package com.retroconsole.bridge;

import com.sun.jna.*;
import com.sun.jna.ptr.*;

/**
 * JNA interface to libretro API.
 * Maps the essential libretro C functions to Java calls.
 * 
 * A libretro core (.dll/.so) is loaded via Native.load() and cast to this interface.
 * 
 * Reference: https://github.com/libretro/libretro-common/include/libretro.h
 */
public interface LibretroBridge extends Library {

    // --- Pixel formats ---
    int RETRO_PIXEL_FORMAT_0RGB1555 = 0;
    int RETRO_PIXEL_FORMAT_XRGB8888 = 1;
    int RETRO_PIXEL_FORMAT_RGB565   = 2;

    // --- Device types ---
    int RETRO_DEVICE_NONE       = 0;
    int RETRO_DEVICE_JOYPAD     = 1;
    int RETRO_DEVICE_MOUSE      = 2;
    int RETRO_DEVICE_KEYBOARD   = 3;
    int RETRO_DEVICE_LIGHTGUN   = 4;
    int RETRO_DEVICE_ANALOG     = 5;
    int RETRO_DEVICE_POINTER    = 6;

    // --- Joypad buttons ---
    int RETRO_DEVICE_ID_JOYPAD_B      = 0;
    int RETRO_DEVICE_ID_JOYPAD_Y      = 1;
    int RETRO_DEVICE_ID_JOYPAD_SELECT = 2;
    int RETRO_DEVICE_ID_JOYPAD_START  = 3;
    int RETRO_DEVICE_ID_JOYPAD_UP     = 4;
    int RETRO_DEVICE_ID_JOYPAD_DOWN   = 5;
    int RETRO_DEVICE_ID_JOYPAD_LEFT   = 6;
    int RETRO_DEVICE_ID_JOYPAD_RIGHT  = 7;
    int RETRO_DEVICE_ID_JOYPAD_A      = 8;
    int RETRO_DEVICE_ID_JOYPAD_X      = 9;
    int RETRO_DEVICE_ID_JOYPAD_L      = 10;
    int RETRO_DEVICE_ID_JOYPAD_R      = 11;
    int RETRO_DEVICE_ID_JOYPAD_L2     = 12;
    int RETRO_DEVICE_ID_JOYPAD_R2     = 13;
    int RETRO_DEVICE_ID_JOYPAD_L3     = 14;
    int RETRO_DEVICE_ID_JOYPAD_R3     = 15;

    // --- Analog sticks ---
    int RETRO_DEVICE_INDEX_ANALOG_LEFT   = 0;
    int RETRO_DEVICE_INDEX_ANALOG_RIGHT  = 1;
    int RETRO_DEVICE_ID_ANALOG_X         = 0;
    int RETRO_DEVICE_ID_ANALOG_Y         = 1;

    // --- Environment commands ---
    int RETRO_ENVIRONMENT_GET_CAN_DUPE         = 1;
    int RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY  = 9;
    int RETRO_ENVIRONMENT_SET_PIXEL_FORMAT      = 10;
    int RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY    = 11;
    int RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS = 12;
    int RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS = 13;
    int RETRO_ENVIRONMENT_SET_GEOMETRY          = 37;
    int RETRO_ENVIRONMENT_SET_VARIABLES         = 16;
    int RETRO_ENVIRONMENT_GET_VARIABLE          = 17;
    int RETRO_ENVIRONMENT_SET_CONTROLLER_INFO   = 35;
    int RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS = 15;
    int RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK    = 27; // actually 31 per log, let me check
    int RETRO_ENVIRONMENT_GET_INPUT_BITMASKS    = 52;
    int RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO    = 32; // cmd 28 in Flycast?
    // cmd=55 — might be RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO or similar

    // --- Memory types ---
    int RETRO_MEMORY_SAVE_RAM    = 0;
    int RETRO_MEMORY_RTC         = 1;
    int RETRO_MEMORY_SYSTEM_RAM  = 2;
    int RETRO_MEMORY_VIDEO_RAM   = 3;

    // === Core lifecycle ===
    void retro_init();
    void retro_deinit();
    void retro_run();
    void retro_reset();

    // === Game loading ===
    boolean retro_load_game(RetroGameInfo game);
    void retro_unload_game();

    // === System info ===
    void retro_get_system_info(RetroSystemInfo info);
    void retro_get_system_av_info(RetroSystemAVInfo info);

    // === Controller ===
    void retro_set_controller_port_device(int port, int device);

    // === Input polling ===
    void retro_set_input_poll(Pointer callback);
    void retro_set_input_state(Pointer callback);

    // === Serialization (save states) ===
    long retro_serialize_size();
    boolean retro_serialize(Pointer data, long size);
    boolean retro_unserialize(Pointer data, long size);

    // === Memory ===
    Pointer retro_get_memory_data(int id);
    long retro_get_memory_size(int id);

    // === Cheats ===
    void retro_cheat_reset();
    void retro_cheat_set(int index, boolean enabled, String code);

    // === Region ===
    int retro_get_region();

    // === API version ===
    int retro_api_version();

    // ================================================================
    // Callbacks set BY the frontend TO the core
    // ================================================================

    /**
     * Environment callback. Core calls this to query/set runtime config.
     * Signature: boolean environment(int cmd, Pointer data)
     */
    interface RetroEnvironment extends Callback {
        boolean callback(int cmd, Pointer data);
    }

    /**
     * Video refresh callback. Core calls this to deliver a frame.
     * Signature: void video_refresh(Pointer data, int width, int height, long pitch)
     */
    interface RetroVideoRefresh extends Callback {
        void callback(Pointer data, int width, int height, long pitch);
    }

    /**
     * Audio sample callback (interleaved stereo).
     */
    interface RetroAudioSample extends Callback {
        void callback(short left, short right);
    }

    /**
     * Audio batch callback. Returns number of frames written.
     */
    interface RetroAudioSampleBatch extends Callback {
        long callback(Pointer data, long frames);
    }

    /**
     * Input poll callback. Core calls this before reading input state.
     */
    interface RetroInputPoll extends Callback {
        void callback();
    }

    /**
     * Input state callback. Returns button/analog state.
     * Signature: short input_state(int port, int device, int index, int id)
     */
    interface RetroInputState extends Callback {
        short callback(int port, int device, int index, int id);
    }

    /**
     * Log callback. Cores use this to log messages.
     * Signature: void (*retro_log_printf_t)(enum retro_log_level level, const char *fmt, ...)
     */
    interface RetroLogCallback extends Callback {
        void callback(int level, String fmt);
    }

    // === Set callbacks (called by frontend before retro_init) ===
    void retro_set_environment(RetroEnvironment callback);
    void retro_set_video_refresh(RetroVideoRefresh callback);
    void retro_set_audio_sample(RetroAudioSample callback);
    void retro_set_audio_sample_batch(RetroAudioSampleBatch callback);
    void retro_set_input_poll(RetroInputPoll callback);
    void retro_set_input_state(RetroInputState callback);

    // ================================================================
    // JNA Structures used by libretro
    // ================================================================

    /** struct retro_game_info */
    class RetroGameInfo extends Structure {
        public String path;
        public Pointer data;
        public long size;
        public String meta;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("path", "data", "size", "meta");
        }
    }

    /** struct retro_system_info */
    class RetroSystemInfo extends Structure {
        public String library_name;
        public String library_version;
        public String valid_extensions;
        public boolean need_fullpath;
        public boolean block_extract;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("library_name", "library_version",
                    "valid_extensions", "need_fullpath", "block_extract");
        }
    }

    /** struct retro_system_av_info */
    class RetroSystemAVInfo extends Structure {
        public RetroGameGeometry geometry;
        public double timing_fps;
        public double timing_sample_rate;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("geometry", "timing_fps", "timing_sample_rate");
        }
    }

    /** struct retro_game_geometry */
    class RetroGameGeometry extends Structure {
        public int base_width;
        public int base_height;
        public int max_width;
        public int max_height;
        public float aspect_ratio;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("base_width", "base_height",
                    "max_width", "max_height", "aspect_ratio");
        }
    }

    /** struct retro_variable — for RETRO_ENVIRONMENT_SET_VARIABLES */
    class RetroVariable extends Structure {
        public String key;
        public String value;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("key", "value");
        }
    }
}
