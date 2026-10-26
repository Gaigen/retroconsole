package com.retroconsole.fabric.mixin;

import com.retroconsole.client.GamepadScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the crosshair and hotbar while the gamepad screen is open, replacing
 * the NeoForge {@code RenderGuiLayerEvent} cancel in {@code GamepadClientEvents}.
 *
 * <p>NeoForge can cancel individual GUI layers by name; Fabric has no such hook
 * for 1.21.1, so the two renderers behind those layers are cancelled at their
 * heads. Vanilla draws the hotbar in {@code renderItemHotbar} (NeoForge splits
 * that one method out of {@code renderHotbarAndDecorations} and names it the
 * hotbar layer), so cancelling it leaves health and experience on screen.
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void retroconsole$hideCrosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof GamepadScreen) {
            ci.cancel();
        }
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void retroconsole$hideHotbar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof GamepadScreen) {
            ci.cancel();
        }
    }
}
