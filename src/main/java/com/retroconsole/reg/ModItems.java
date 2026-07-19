package com.retroconsole.reg;

import com.retroconsole.item.GamepadItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("retroconsole");

    public static final DeferredItem<GamepadItem> GAMEPAD = ITEMS.register(
            "gamepad",
            () -> new GamepadItem(new Item.Properties().stacksTo(1))
    );

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    private ModItems() {}
}
