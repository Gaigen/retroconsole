package com.retroconsole.block;

import com.retroconsole.reg.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class ScreenBlockEntity extends BlockEntity {

    @Nullable
    private UUID consoleId;
    private int xIndex;
    private int yIndex;
    private int gridWidth = 1;
    private int gridHeight = 1;
    private boolean assembled;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCREEN_BE, pos, state);
    }

    @Nullable
    public UUID getConsoleId() { return consoleId; }
    public int getXIndex() { return xIndex; }
    public int getYIndex() { return yIndex; }
    public int getGridWidth() { return gridWidth; }
    public int getGridHeight() { return gridHeight; }

    void setGrid(int xIndex, int yIndex, int width, int height, @Nullable UUID consoleId) {
        boolean changed = !assembled
                || this.xIndex != xIndex || this.yIndex != yIndex
                || this.gridWidth != width || this.gridHeight != height
                || !java.util.Objects.equals(this.consoleId, consoleId);
        this.assembled = true;
        this.xIndex = xIndex;
        this.yIndex = yIndex;
        this.gridWidth = width;
        this.gridHeight = height;
        this.consoleId = consoleId;
        if (changed) {
            setChanged();
            if (level instanceof ServerLevel serverLevel) {
                syncToTrackingClients(serverLevel);
            }
        }
    }

    /** Push grid/consoleId to clients already watching this chunk (not only on rejoin). */
    private void syncToTrackingClients(ServerLevel serverLevel) {
        // Do not sendBlockUpdated here — same-state updates reset client-side block breaking.
        ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(this);
        BlockPos pos = worldPosition;
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (player.level() != serverLevel || player.connection == null) continue;
            if (!player.getChunkTrackingView().contains(ChunkPos.containing(pos))) continue;
            player.connection.send(packet);
        }
    }

    /**
     * Whole screens are rebuilt from the saved grid whenever their chunk comes
     * back, so the trigger is a level attach rather than a tick. Vanilla can
     * attach twice per chunk unpack; only act on the first one.
     */
    @Override
    public void setLevel(Level level) {
        Level previous = this.level;
        super.setLevel(level);
        if (level == previous) return;
        if (level instanceof ServerLevel serverLevel && !assembled) {
            ScreenMultiblocks.scheduleRebuild(serverLevel, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (consoleId != null) {
            output.store("ConsoleId", UUIDUtil.CODEC, consoleId);
        }
        if (assembled) {
            output.putInt("GridX", xIndex);
            output.putInt("GridY", yIndex);
            output.putInt("GridW", gridWidth);
            output.putInt("GridH", gridHeight);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        consoleId = input.read("ConsoleId", UUIDUtil.CODEC).orElse(null);
        assembled = input.getInt("GridW").isPresent();
        xIndex = input.getIntOr("GridX", 0);
        yIndex = input.getIntOr("GridY", 0);
        gridWidth = Math.max(1, input.getIntOr("GridW", 1));
        gridHeight = Math.max(1, input.getIntOr("GridH", 1));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
