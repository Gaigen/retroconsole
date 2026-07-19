package com.retroconsole.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.retroconsole.client.RetroInputBindings;
import com.retroconsole.network.RetroAnalogPacket;
import com.retroconsole.network.RetroInputPacket;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

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

    public RetroInputSender(BlockPos consolePos) {
        this.consolePos = consolePos;
    }

    public BlockPos consolePos() {
        return consolePos;
    }

    public boolean handleKeyPressed(InputConstants.Key key) {
        int buttonId = RetroInputBindings.mapButton(key);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, true));
            return true;
        }
        return handleAnalogKey(key, true);
    }

    public boolean handleKeyReleased(InputConstants.Key key) {
        int buttonId = RetroInputBindings.mapButton(key);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, false));
            return true;
        }
        return handleAnalogKey(key, false);
    }

    public void releaseAll() {
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.BUTTONS) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, bind.retroId(), false));
        }
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.DS_BUTTONS) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, bind.retroId(), false));
        }
    }

    public void sendAnalogZeros() {
        sentLx = sentLy = sentRx = sentRy = 0;
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, (short) 0, (short) 0, (short) 0, (short) 0));
    }

    public RetroInputBindings.StickState rightStick() {
        return rightStick;
    }

    private boolean handleAnalogKey(InputConstants.Key key, boolean pressed) {
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
        short lx = leftStick.analogX();
        short ly = leftStick.analogY();
        short rx = rightStick.analogX();
        short ry = rightStick.analogY();
        if (lx == sentLx && ly == sentLy && rx == sentRx && ry == sentRy) return;
        sentLx = lx;
        sentLy = ly;
        sentRx = rx;
        sentRy = ry;
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, lx, ly, rx, ry));
    }
}
