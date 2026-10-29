package com.retroconsole.client.input;

import com.retroconsole.client.RetroInputBindings;
import com.retroconsole.network.NetAccess;
import com.retroconsole.network.RetroAnalogPacket;
import com.retroconsole.network.RetroInputPacket;
import com.retroconsole.network.payload.RetroPayloads;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;

/**
 * Sends libretro button / analog input packets for a linked console.
 * Shared by {@link com.retroconsole.client.TvScreen} and {@link com.retroconsole.client.GamepadScreen}.
 */
public final class RetroInputSender {

    private final BlockPos consolePos;
    private final RetroInputBindings.StickState leftStick = new RetroInputBindings.StickState();
    private final RetroInputBindings.StickState rightStick = new RetroInputBindings.StickState();
    private short sentLx;
    private short sentLy;
    private short sentRx;
    private short sentRy;

    // --- Gamepad analog state (merged with keyboard analog) ---
    private short gpLx, gpLy, gpRx, gpRy;

    public RetroInputSender(BlockPos consolePos) {
        this.consolePos = consolePos;
    }

    public BlockPos consolePos() {
        return consolePos;
    }

    /** Direct button send by retro id (gamepad path, bypasses KeyMapping). */
    public void sendButton(int retroId, boolean pressed) {
        NetAccess.sendToServer(RetroPayloads.INPUT, new RetroInputPacket(consolePos, retroId, pressed));
    }

    /** Gamepad stick state after deadzone; merged with keyboard analog. */
    public void setGamepadAnalog(short lx, short ly, short rx, short ry) {
        if (lx == gpLx && ly == gpLy && rx == gpRx && ry == gpRy) return;
        gpLx = lx;
        gpLy = ly;
        gpRx = rx;
        gpRy = ry;
        sendAnalogIfChanged();
    }

    public boolean handleKeyPressed(KeyEvent key) {
        int buttonId = RetroInputBindings.mapButton(key);
        if (buttonId >= 0) {
            NetAccess.sendToServer(RetroPayloads.INPUT, new RetroInputPacket(consolePos, buttonId, true));
            return true;
        }
        return handleAnalogKey(key, true);
    }

    public boolean handleKeyReleased(KeyEvent key) {
        int buttonId = RetroInputBindings.mapButton(key);
        if (buttonId >= 0) {
            NetAccess.sendToServer(RetroPayloads.INPUT, new RetroInputPacket(consolePos, buttonId, false));
            return true;
        }
        return handleAnalogKey(key, false);
    }

    public void releaseAll() {
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.BUTTONS) {
            NetAccess.sendToServer(RetroPayloads.INPUT, new RetroInputPacket(consolePos, bind.retroId(), false));
        }
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.DS_BUTTONS) {
            NetAccess.sendToServer(RetroPayloads.INPUT, new RetroInputPacket(consolePos, bind.retroId(), false));
        }
    }

    public void clearKeyboardAnalog() { leftStick.clear(); rightStick.clear(); }

    public void sendAnalogZeros() {
        gpLx = gpLy = gpRx = gpRy = 0;
        sentLx = sentLy = sentRx = sentRy = 0;
        NetAccess.sendToServer(RetroPayloads.ANALOG, new RetroAnalogPacket(consolePos, (short) 0, (short) 0, (short) 0, (short) 0));
    }

    public RetroInputBindings.StickState rightStick() {
        return rightStick;
    }

    private boolean handleAnalogKey(KeyEvent key, boolean pressed) {
        boolean leftChanged = RetroInputBindings.updateStick(
                RetroInputBindings.LEFT_STICK, key, pressed, leftStick);
        boolean rightChanged = RetroInputBindings.updateStick(
                RetroInputBindings.RIGHT_STICK, key, pressed, rightStick);
        if (leftChanged || rightChanged) {
            sendAnalogIfChanged();
        }
        return leftChanged || rightChanged;
    }

    private void sendAnalogIfChanged() {
        short lx = merge(leftStick.analogX(), gpLx);
        short ly = merge(leftStick.analogY(), gpLy);
        short rx = merge(rightStick.analogX(), gpRx);
        short ry = merge(rightStick.analogY(), gpRy);
        if (lx == sentLx && ly == sentLy && rx == sentRx && ry == sentRy) return;
        sentLx = lx;
        sentLy = ly;
        sentRx = rx;
        sentRy = ry;
        NetAccess.sendToServer(RetroPayloads.ANALOG, new RetroAnalogPacket(consolePos, lx, ly, rx, ry));
    }

    private static short merge(short keyboard, short gamepad) {
        int sum = keyboard + gamepad;
        if (sum < -32768) return (short) -32768;
        if (sum > 32767) return (short) 32767;
        return (short) sum;
    }
}
