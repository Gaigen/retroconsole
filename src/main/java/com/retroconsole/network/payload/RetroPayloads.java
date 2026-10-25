package com.retroconsole.network.payload;

import com.retroconsole.network.RetroAnalogPacket;
import com.retroconsole.network.RetroArtPacket;
import com.retroconsole.network.RetroAudioPayload;
import com.retroconsole.network.RetroAudioSettingsPacket;
import com.retroconsole.network.RetroCoopPacket;
import com.retroconsole.network.RetroCoreSelectPacket;
import com.retroconsole.network.RetroFramePacket;
import com.retroconsole.network.RetroInputPacket;
import com.retroconsole.network.RetroKeyPacket;
import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.network.RetroLibraryRequestPacket;
import com.retroconsole.network.RetroMousePacket;
import com.retroconsole.network.RetroOpenScreenPacket;
import com.retroconsole.network.RetroPointerPacket;
import com.retroconsole.network.RetroPowerOffPacket;
import com.retroconsole.network.RetroSaveStatePacket;
import com.retroconsole.network.RetroStopConsolePacket;
import com.retroconsole.network.RetroViewPacket;
import com.retroconsole.network.ServerPayloadHandlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every payload the mod speaks, in one place: id, codec and server-side handler.
 *
 * <p>Shared code refers to a payload by its {@link NetPayload} constant, never by
 * a loader's payload class, so {@code NetAccess.sendToPlayer(player, FRAME, frame)}
 * is the only call shape a module needs.
 *
 * <p>Client-side handlers deliberately live in {@code client/ClientPacketHandlers}:
 * they import client-only classes, so keeping them out of this table is what lets
 * the shared code stay free of {@code com.retroconsole.client}.
 */
public final class RetroPayloads {

    private RetroPayloads() {}

    // ---- server to client -------------------------------------------------

    public static final NetPayload<RetroFramePacket> FRAME =
            NetPayload.of("frame", RetroFramePacket.STREAM_CODEC);
    public static final NetPayload<RetroAudioPayload> AUDIO =
            NetPayload.of("audio", RetroAudioPayload.STREAM_CODEC);
    public static final NetPayload<RetroStopConsolePacket> STOP_CONSOLE =
            NetPayload.of("stop_console", RetroStopConsolePacket.STREAM_CODEC);
    public static final NetPayload<RetroOpenScreenPacket> OPEN_SCREEN =
            NetPayload.of("open_screen", RetroOpenScreenPacket.STREAM_CODEC);
    public static final NetPayload<RetroLibraryPacket> LIBRARY =
            NetPayload.of("library", RetroLibraryPacket.STREAM_CODEC);
    public static final NetPayload<RetroArtPacket> ART =
            NetPayload.of("art", RetroArtPacket.STREAM_CODEC);

    // ---- client to server -------------------------------------------------

    public static final NetPayload<RetroPointerPacket> POINTER =
            NetPayload.of("pointer", RetroPointerPacket.STREAM_CODEC);
    public static final NetPayload<RetroKeyPacket> KEY =
            NetPayload.of("key", RetroKeyPacket.STREAM_CODEC);
    public static final NetPayload<RetroMousePacket> MOUSE =
            NetPayload.of("mouse", RetroMousePacket.STREAM_CODEC);
    public static final NetPayload<RetroInputPacket> INPUT =
            NetPayload.of("input", RetroInputPacket.STREAM_CODEC);
    public static final NetPayload<RetroAnalogPacket> ANALOG =
            NetPayload.of("analog", RetroAnalogPacket.STREAM_CODEC);
    public static final NetPayload<RetroViewPacket> VIEW =
            NetPayload.of("view", RetroViewPacket.STREAM_CODEC);
    public static final NetPayload<RetroCoreSelectPacket> CORE_SELECT =
            NetPayload.of("core_select", RetroCoreSelectPacket.STREAM_CODEC);
    public static final NetPayload<RetroSaveStatePacket> SAVE_STATE =
            NetPayload.of("save_state", RetroSaveStatePacket.STREAM_CODEC);
    public static final NetPayload<RetroPowerOffPacket> POWER_OFF =
            NetPayload.of("power_off", RetroPowerOffPacket.STREAM_CODEC);
    public static final NetPayload<RetroLibraryRequestPacket> LIBRARY_REQUEST =
            NetPayload.of("library_request", RetroLibraryRequestPacket.STREAM_CODEC);
    public static final NetPayload<RetroCoopPacket> COOP =
            NetPayload.of("coop", RetroCoopPacket.STREAM_CODEC);
    public static final NetPayload<RetroAudioSettingsPacket> AUDIO_SETTINGS =
            NetPayload.of("audio_settings", RetroAudioSettingsPacket.STREAM_CODEC);

    public static final List<NetPayload<?>> CLIENT_BOUND = List.<NetPayload<?>>of(
            FRAME, AUDIO, STOP_CONSOLE, OPEN_SCREEN, LIBRARY, ART);

    public static final List<NetPayload<?>> SERVER_BOUND = List.<NetPayload<?>>of(
            POINTER, KEY, MOUSE, INPUT, ANALOG, VIEW, CORE_SELECT, SAVE_STATE,
            POWER_OFF, LIBRARY_REQUEST, COOP, AUDIO_SETTINGS);

    public static final List<NetPayload<?>> ALL = all();

    private static List<NetPayload<?>> all() {
        List<NetPayload<?>> all = new ArrayList<>(CLIENT_BOUND);
        all.addAll(SERVER_BOUND);
        return List.copyOf(all);
    }

    // ---- server-side handler table ----------------------------------------

    private static final Map<String, NetHandler<?>> SERVER_HANDLERS = new HashMap<>();

    static {
        server(POINTER, ServerPayloadHandlers::handlePointer);
        server(KEY, ServerPayloadHandlers::handleKey);
        server(MOUSE, ServerPayloadHandlers::handleMouse);
        server(INPUT, ServerPayloadHandlers::handleInput);
        server(ANALOG, ServerPayloadHandlers::handleAnalog);
        server(VIEW, ServerPayloadHandlers::handleView);
        server(CORE_SELECT, ServerPayloadHandlers::handleCoreSelect);
        server(SAVE_STATE, ServerPayloadHandlers::handleSaveState);
        server(POWER_OFF, ServerPayloadHandlers::handlePowerOff);
        server(LIBRARY_REQUEST, ServerPayloadHandlers::handleLibraryRequest);
        server(COOP, ServerPayloadHandlers::handleCoop);
        server(AUDIO_SETTINGS, ServerPayloadHandlers::handleAudioSettings);
    }

    private static <T> void server(NetPayload<T> definition, NetHandler<T> handler) {
        SERVER_HANDLERS.put(definition.id(), handler);
    }

    @SuppressWarnings("unchecked")
    public static <T> NetHandler<T> serverHandler(NetPayload<T> definition) {
        return (NetHandler<T>) require(SERVER_HANDLERS, definition, "server");
    }

    private static NetHandler<?> require(Map<String, NetHandler<?>> table,
                                        NetPayload<?> definition, String side) {
        NetHandler<?> handler = table.get(definition.id());
        if (handler == null) {
            throw new IllegalStateException("No " + side + " handler for payload '" + definition.id() + "'");
        }
        return handler;
    }
}
