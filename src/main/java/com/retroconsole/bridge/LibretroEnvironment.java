package com.retroconsole.bridge;

/**
 * libretro {@code RETRO_ENVIRONMENT_*} command IDs from libretro.h.
 * @see <a href="https://github.com/libretro/libretro-common/blob/master/include/libretro.h">libretro.h</a>
 */
public final class LibretroEnvironment {

    public static final int EXPERIMENTAL = 0x10000;
    public static final int PRIVATE      = 0x20000;

    public static final int SET_ROTATION              = 1;
    public static final int GET_OVERSCAN              = 2;
    public static final int GET_CAN_DUPE              = 3;
    public static final int SET_MESSAGE               = 6;
    public static final int SHUTDOWN                  = 7;
    public static final int SET_PERFORMANCE_LEVEL     = 8;
    public static final int GET_SYSTEM_DIRECTORY      = 9;
    public static final int SET_PIXEL_FORMAT          = 10;
    public static final int SET_INPUT_DESCRIPTORS     = 11;
    public static final int SET_KEYBOARD_CALLBACK     = 12;
    public static final int SET_DISK_CONTROL_INTERFACE = 13;
    public static final int SET_HW_RENDER             = 14;
    public static final int GET_VARIABLE              = 15;
    public static final int SET_VARIABLES             = 16;
    public static final int GET_VARIABLE_UPDATE       = 17;
    public static final int SET_SUPPORT_NO_GAME       = 18;
    public static final int GET_LIBRETRO_PATH         = 19;
    public static final int SET_DISK_CONTROL_EXT_INTERFACE = 58;
    public static final int SET_FRAME_TIME_CALLBACK   = 21;
    public static final int SET_AUDIO_CALLBACK        = 22;
    public static final int GET_RUMBLE_INTERFACE      = 23;
    public static final int GET_INPUT_DEVICE_CAPABILITIES = 24;
    public static final int GET_LOG_INTERFACE         = 27;
    public static final int GET_PERF_INTERFACE        = 28;
    public static final int GET_SAVE_DIRECTORY        = 31;
    public static final int SET_SYSTEM_AV_INFO        = 32;
    public static final int SET_SUBSYSTEM_INFO        = 34;
    public static final int SET_CONTROLLER_INFO         = 35;
    public static final int SET_GEOMETRY              = 37;
    public static final int GET_USERNAME              = 38;
    public static final int GET_LANGUAGE              = 39;
    public static final int SET_SERIALIZATION_QUIRKS  = 44;
    public static final int GET_CORE_OPTIONS_VERSION  = 52;
    public static final int SET_CORE_OPTIONS          = 53;
    public static final int SET_CORE_OPTIONS_DISPLAY  = 55;
    public static final int GET_PREFERRED_HW_RENDER   = 56;
    public static final int GET_DISK_CONTROL_INTERFACE_VERSION = 57;
    public static final int GET_MESSAGE_INTERFACE_VERSION = 59;
    public static final int SET_MINIMUM_AUDIO_LATENCY = 63;
    public static final int SET_FASTFORWARDING_OVERRIDE = 64;
    public static final int SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK = 69;
    public static final int SET_VARIABLE                       = 70;
    public static final int SET_CORE_OPTIONS_V2                    = 67;

    public static final int GET_INPUT_BITMASKS        = 51 | EXPERIMENTAL;
    public static final int SET_HW_SHARED_CONTEXT     = 87 | EXPERIMENTAL;
    public static final int SET_CORE_OPTIONS_V2_INTL          = 68;
    public static final int SET_MESSAGE_EXT                   = 60;
    public static final int GET_VFS_INTERFACE                 = 45 | EXPERIMENTAL;
    public static final int SET_MEMORY_MAPS                     = 36 | EXPERIMENTAL;
    public static final int GET_CURRENT_SOFTWARE_FRAMEBUFFER    = 40 | EXPERIMENTAL;
    public static final int SET_AUDIO_BUFFER_STATUS_CALLBACK      = 62;
    public static final int GET_AUDIO_VIDEO_ENABLE                = 47;

    public static final int RETRO_HW_CONTEXT_OPENGL_CORE = 3;

    /** Strip environment flags; libretro command id is in the low 16 bits. */
    public static int normalize(int cmd) {
        return cmd & 0xFFFF;
    }

    public static String name(int cmd) {
        int c = normalize(cmd);
        return switch (c) {
            case SET_ROTATION -> "SET_ROTATION";
            case GET_OVERSCAN -> "GET_OVERSCAN";
            case GET_CAN_DUPE -> "GET_CAN_DUPE";
            case SET_MESSAGE -> "SET_MESSAGE";
            case SHUTDOWN -> "SHUTDOWN";
            case SET_PERFORMANCE_LEVEL -> "SET_PERFORMANCE_LEVEL";
            case GET_SYSTEM_DIRECTORY -> "GET_SYSTEM_DIRECTORY";
            case SET_PIXEL_FORMAT -> "SET_PIXEL_FORMAT";
            case SET_INPUT_DESCRIPTORS -> "SET_INPUT_DESCRIPTORS";
            case SET_KEYBOARD_CALLBACK -> "SET_KEYBOARD_CALLBACK";
            case SET_DISK_CONTROL_INTERFACE -> "SET_DISK_CONTROL_INTERFACE";
            case SET_HW_RENDER -> "SET_HW_RENDER";
            case GET_VARIABLE -> "GET_VARIABLE";
            case SET_VARIABLES -> "SET_VARIABLES";
            case GET_VARIABLE_UPDATE -> "GET_VARIABLE_UPDATE";
            case SET_SUPPORT_NO_GAME -> "SET_SUPPORT_NO_GAME";
            case GET_LIBRETRO_PATH -> "GET_LIBRETRO_PATH";
            case SET_AUDIO_CALLBACK -> "SET_AUDIO_CALLBACK";
            case GET_RUMBLE_INTERFACE -> "GET_RUMBLE_INTERFACE";
            case GET_LOG_INTERFACE -> "GET_LOG_INTERFACE";
            case GET_PERF_INTERFACE -> "GET_PERF_INTERFACE";
            case GET_SAVE_DIRECTORY -> "GET_SAVE_DIRECTORY";
            case SET_SYSTEM_AV_INFO -> "SET_SYSTEM_AV_INFO";
            case SET_CONTROLLER_INFO -> "SET_CONTROLLER_INFO";
            case SET_GEOMETRY -> "SET_GEOMETRY";
            case GET_USERNAME -> "GET_USERNAME";
            case GET_LANGUAGE -> "GET_LANGUAGE";
            case SET_SERIALIZATION_QUIRKS -> "SET_SERIALIZATION_QUIRKS";
            case GET_CORE_OPTIONS_VERSION -> "GET_CORE_OPTIONS_VERSION";
            case SET_CORE_OPTIONS -> "SET_CORE_OPTIONS";
            case SET_CORE_OPTIONS_DISPLAY -> "SET_CORE_OPTIONS_DISPLAY";
            case SET_CORE_OPTIONS_V2 -> "SET_CORE_OPTIONS_V2";
            case GET_PREFERRED_HW_RENDER -> "GET_PREFERRED_HW_RENDER";
            case GET_DISK_CONTROL_INTERFACE_VERSION -> "GET_DISK_CONTROL_INTERFACE_VERSION";
            case SET_DISK_CONTROL_EXT_INTERFACE -> "SET_DISK_CONTROL_EXT_INTERFACE";
            case GET_MESSAGE_INTERFACE_VERSION -> "GET_MESSAGE_INTERFACE_VERSION";
            case SET_MESSAGE_EXT -> "SET_MESSAGE_EXT";
            case SET_MINIMUM_AUDIO_LATENCY -> "SET_MINIMUM_AUDIO_LATENCY";
            case GET_AUDIO_VIDEO_ENABLE -> "GET_AUDIO_VIDEO_ENABLE";
            case SET_CORE_OPTIONS_V2_INTL -> "SET_CORE_OPTIONS_V2_INTL";
            case SET_VARIABLE -> "SET_VARIABLE";
            case 45 -> "GET_VFS_INTERFACE";
            case 36 -> "SET_MEMORY_MAPS";
            case 40 -> "GET_CURRENT_SOFTWARE_FRAMEBUFFER";
            case 51 -> "GET_INPUT_BITMASKS";
            case 87 -> "SET_HW_SHARED_CONTEXT";
            default -> "UNKNOWN(" + cmd + ")";
        };
    }

    private LibretroEnvironment() {}
}
