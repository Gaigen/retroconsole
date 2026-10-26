package com.retroconsole.fabric.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.retroconsole.client.ModKeys;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps vanilla bindings in charge of the keys the console bindings duplicate.
 *
 * <p>Vanilla holds one mapping per key ({@code MAP}) and {@code resetMapping}
 * fills it from a hash map of every mapping, so for a shared key the winner is
 * whichever mapping happens to be visited last. The console bindings duplicate
 * several vanilla defaults on purpose (W is R1, Q is L1, T and P are the right
 * stick and touch cursor, ...), which then silently killed movement, dropping,
 * chat and the other vanilla actions instead of just being cosmetic. NeoForge
 * keeps a multi-entry lookup and has no such race; Fabric needs the table
 * rebuilt with the vanilla mappings claiming their keys first. The console
 * bindings only read raw key events, so they never ask {@code MAP} for
 * {@code isDown} or clicks and lose nothing here.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {

    @Shadow
    private static Map<String, KeyMapping> ALL;

    @Shadow
    private static Map<InputConstants.Key, KeyMapping> MAP;

    @Inject(method = "resetMapping", at = @At("TAIL"))
    private static void retroconsole$vanillaBindingsFirst(CallbackInfo ci) {
        Map<InputConstants.Key, KeyMapping> resolved = new LinkedHashMap<>(MAP.size());
        for (KeyMapping mapping : ALL.values()) {
            if (!ModKeys.ALL.contains(mapping)) {
                resolved.put(((KeyMappingAccessor) mapping).retroconsole$key(), mapping);
            }
        }
        for (KeyMapping mapping : ALL.values()) {
            if (ModKeys.ALL.contains(mapping)) {
                resolved.putIfAbsent(((KeyMappingAccessor) mapping).retroconsole$key(), mapping);
            }
        }
        MAP.clear();
        MAP.putAll(resolved);
    }
}
