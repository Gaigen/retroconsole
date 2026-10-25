package com.retroconsole.block;

import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.server.ServerConsoles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class RetroConsoleBlockEntity extends BlockEntity {

    private UUID consoleId;
    private BlockPos lastTrackedPos;
    private String romId = "";
    private String coreName = "";
    private UUID ownerId;
    private boolean pendingLoadAuto;

    public RetroConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RETRO_CONSOLE_BE.get(), pos, state);
    }

    /** Synced id; null on client until chunk/BE packet arrives. */
    public UUID getConsoleId() {
        return consoleId;
    }

    /** Server: return id, creating and persisting one if needed. */
    public UUID getOrAssignConsoleId() {
        if (level != null && level.isClientSide()) {
            return consoleId;
        }
        if (consoleId == null) {
            consoleId = UUID.randomUUID();
            setChanged();
            syncBlockEntityToClients();
        }
        return consoleId;
    }

    /** Server tick: track moving contraptions for frame distance checks only. */
    static void serverTick(RetroConsoleBlockEntity be) {
        if (be.level == null || be.level.isClientSide() || be.consoleId == null) return;
        BlockPos pos = be.getBlockPos();
        if (be.lastTrackedPos != null && be.lastTrackedPos.equals(pos)) return;
        be.lastTrackedPos = pos.immutable();
        ServerConsoles.updatePosition(be.consoleId, pos);
    }

    private void syncBlockEntityToClients() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(this);
        BlockPos pos = worldPosition;
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (player.level() != serverLevel || player.connection == null) continue;
            if (!player.getChunkTrackingView().contains(new ChunkPos(pos))) continue;
            player.connection.send(packet);
        }
    }

    public String getRomId() {
        return romId;
    }

    public void setRomId(String romId) {
        String old = this.romId;
        this.romId = romId;
        setChanged();
        if (level != null && !level.isClientSide()) {
            syncBlockEntityToClients();
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
            syncBlockEntityToClients();
        }
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
        setChanged();
    }

    public void selectGame(String coreName, String romId, UUID ownerId, boolean loadAuto) {
        if (level == null || level.isClientSide()) return;
        this.ownerId = ownerId;
        this.coreName = coreName;
        this.pendingLoadAuto = loadAuto;
        this.romId = romId;
        setChanged();
        syncBlockEntityToClients();
        if (romId.isEmpty()) {
            stopEmulator();
        } else {
            stopEmulator();
            startEmulator();
        }
    }

    public void powerOff() {
        if (level == null || level.isClientSide()) return;
        setRomId("");
    }

    public boolean isControlledBy(ServerPlayer player) {
        return ownerId != null && ownerId.equals(player.getUUID());
    }

    public LibretroCore getCore() {
        if (level == null || level.isClientSide()) return null;
        UUID id = consoleId;
        return id != null ? ServerConsoles.getCore(id) : null;
    }

    private void startEmulator() {
        if (level instanceof ServerLevel && !coreName.isEmpty() && !romId.isEmpty()) {
            UUID id = getOrAssignConsoleId();
            ServerConsoles.startEmulator(id, worldPosition, coreName, romId, ownerId, pendingLoadAuto);
            pendingLoadAuto = false;
        }
    }

    private void stopEmulator() {
        if (level instanceof ServerLevel && consoleId != null) {
            ServerConsoles.stopEmulator(consoleId);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                if (!isRemoved() && serverLevel.isLoaded(worldPosition)) {
                    UUID id = getOrAssignConsoleId();
                    lastTrackedPos = worldPosition.immutable();
                    ServerConsoles.updatePosition(id, worldPosition);
                }
            });
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
        UUID id = level != null && !level.isClientSide() ? getOrAssignConsoleId() : consoleId;
        if (id != null) {
            tag.putUUID("ConsoleId", id);
        }
        tag.putString("RomId", romId);
        tag.putString("CoreName", coreName);
        if (ownerId != null) {
            tag.putUUID("OwnerId", ownerId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        consoleId = tag.hasUUID("ConsoleId") ? tag.getUUID("ConsoleId") : null;
        romId = tag.getString("RomId");
        coreName = tag.getString("CoreName");
        ownerId = tag.hasUUID("OwnerId") ? tag.getUUID("OwnerId") : null;
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
