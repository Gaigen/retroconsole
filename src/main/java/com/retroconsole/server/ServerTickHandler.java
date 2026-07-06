package com.retroconsole.server;

import com.retroconsole.RetroConsole;
import com.retroconsole.network.RetroAudioPayload;
import com.retroconsole.network.RetroFramePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = RetroConsole.MOD_ID)
public class ServerTickHandler {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (var level : event.getServer().getAllLevels()) {
            ServerConsoles.tick(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerConsoles.stopAll();
    }

    /**
     * Send a frame to a specific player.
     */
    public static void sendFrameToPlayer(ServerPlayer player, BlockPos pos,
                                          int[] frame, int width, int height) {
        RetroFramePacket packet = RetroFramePacket.create(pos, frame, width, height);
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendAudioToPlayer(ServerPlayer player, RetroAudioPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
