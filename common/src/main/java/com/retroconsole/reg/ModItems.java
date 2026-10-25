package com.retroconsole.reg;

import com.retroconsole.item.GamepadItem;
import net.minecraft.world.item.Item;

/**
 * Item instances shared by every loader; the loader module registers them.
 */
public final class ModItems {

    public static final GamepadItem GAMEPAD = new GamepadItem(new Item.Properties().stacksTo(1));

    private ModItems() {}
}
