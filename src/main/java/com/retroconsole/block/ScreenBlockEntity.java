package com.retroconsole.block;

import com.retroconsole.reg.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ScreenBlockEntity extends BlockEntity {

    private BlockPos consolePos = BlockPos.ZERO;
    private int xIndex;
    private int yIndex;
    private int gridWidth = 1;
    private int gridHeight = 1;
    /** false — группа не собрана (свежая установка или старый NBT без Grid-тегов). */
    private boolean assembled;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCREEN_BE.get(), pos, state);
    }

    public BlockPos getConsolePos() { return consolePos; }
    public int getXIndex() { return xIndex; }
    public int getYIndex() { return yIndex; }
    public int getGridWidth() { return gridWidth; }
    public int getGridHeight() { return gridHeight; }

    /** Только сервер; вызывается ScreenMultiblocks при пересборке группы. */
    void setGrid(int xIndex, int yIndex, int width, int height, BlockPos consolePos) {
        boolean changed = !assembled
                || this.xIndex != xIndex || this.yIndex != yIndex
                || this.gridWidth != width || this.gridHeight != height
                || !this.consolePos.equals(consolePos);
        this.assembled = true;
        this.xIndex = xIndex;
        this.yIndex = yIndex;
        this.gridWidth = width;
        this.gridHeight = height;
        this.consolePos = consolePos.immutable();
        if (changed) {
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel && !assembled) {
            ScreenMultiblocks.scheduleRebuild(serverLevel, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("ConsoleX", consolePos.getX());
        tag.putInt("ConsoleY", consolePos.getY());
        tag.putInt("ConsoleZ", consolePos.getZ());
        if (assembled) {
            tag.putInt("GridX", xIndex);
            tag.putInt("GridY", yIndex);
            tag.putInt("GridW", gridWidth);
            tag.putInt("GridH", gridHeight);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        consolePos = new BlockPos(tag.getInt("ConsoleX"), tag.getInt("ConsoleY"), tag.getInt("ConsoleZ"));
        assembled = tag.contains("GridW");
        xIndex = tag.getInt("GridX");
        yIndex = tag.getInt("GridY");
        gridWidth = Math.max(1, tag.getInt("GridW"));
        gridHeight = Math.max(1, tag.getInt("GridH"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
