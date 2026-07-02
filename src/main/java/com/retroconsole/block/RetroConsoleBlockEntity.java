package com.retroconsole.block;

import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.server.ServerConsoles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RetroConsoleBlockEntity extends BlockEntity {

    private String romId = "";
    private String coreName = "";

    public RetroConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RETRO_CONSOLE_BE.get(), pos, state);
    }

    public String getRomId() {
        return romId;
    }

    public void setRomId(String romId) {
        String old = this.romId;
        this.romId = romId;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (!romId.isEmpty() && !romId.equals(old)) {
                startEmulator();
            } else if (romId.isEmpty()) {
                stopEmulator();
            }
        }
    }

    public String getCoreName() {
        return coreName;
    }

    public void setCoreName(String coreName) {
        this.coreName = coreName;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void startEmulator() {
        if (level instanceof ServerLevel && !coreName.isEmpty() && !romId.isEmpty()) {
            ServerConsoles.startEmulator(worldPosition, coreName, romId);
        }
    }

    private void stopEmulator() {
        if (level instanceof ServerLevel) {
            ServerConsoles.stopEmulator(worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            stopEmulator();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("RomId", romId);
        tag.putString("CoreName", coreName);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        romId = tag.getString("RomId");
        coreName = tag.getString("CoreName");
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
