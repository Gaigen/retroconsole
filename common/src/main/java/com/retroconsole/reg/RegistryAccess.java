package com.retroconsole.reg;

/**
 * Loader-agnostic registry hooks.
 * NeoForge: DeferredRegister + RegisterEvent.
 * Fabric: Registry.register.
 * Each loader implements and calls during its registration phase.
 */
public final class RegistryAccess {

    private RegistryAccess() {}

    private static Blocks blocks = () -> { };
    private static Items items = () -> { };
    private static BlockEntities blockEntities = () -> { };

    /** Called by the loader entrypoint before registration. */
    public static void setBlocks(Blocks blocks) {
        if (blocks != null) RegistryAccess.blocks = blocks;
    }

    public static void setItems(Items items) {
        if (items != null) RegistryAccess.items = items;
    }

    public static void setBlockEntities(BlockEntities blockEntities) {
        if (blockEntities != null) RegistryAccess.blockEntities = blockEntities;
    }

    public static void registerBlocks() {
        blocks.register();
    }

    public static void registerItems() {
        items.register();
    }

    public static void registerBlockEntities() {
        blockEntities.register();
    }

    public interface Blocks {
        void register();
    }

    public interface Items {
        void register();
    }

    public interface BlockEntities {
        void register();
    }
}
