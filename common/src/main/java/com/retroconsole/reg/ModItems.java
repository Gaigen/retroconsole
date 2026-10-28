package com.retroconsole.reg;

import com.retroconsole.item.GamepadItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Item instances shared by every loader; the loader module registers them.
 */
public final class ModItems {

    public static final GamepadItem GAMEPAD = new GamepadItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("retroconsole", "gamepad")))
            .stacksTo(1));

    private ModItems() {}
}
