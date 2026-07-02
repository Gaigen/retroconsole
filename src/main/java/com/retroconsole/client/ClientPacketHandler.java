package com.retroconsole.client;

import com.retroconsole.network.RetroFramePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming network packets on the client side.
 * Frame packets arrive on the network thread and must be dispatched to the main thread
 * for DynamicTexture operations (OpenGL context required).
 *
 * Note: The NetworkHandler now handles frame packets directly via ctx.enqueueWork(),
 * but this class remains as a standalone utility for alternative packet routing.
 */
public final class ClientPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("ClientPacketHandler");

    private ClientPacketHandler() {}

    /**
     * Handle an incoming frame packet from the server.
     * Decompresses the frame data and updates the client-side texture on the main thread.
     *
     * @param packet the compressed frame packet
     */
    public static void handleFrame(RetroFramePacket packet) {
        Minecraft.getInstance().execute(() -> {
            try {
                int[] frame = packet.decompressFrame();
                if (frame != null) {
                    BlockPos pos = packet.pos();
                    ClientConsoles.updateFrame(pos, frame, packet.width(), packet.height());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle frame packet for {}", packet.pos(), e);
            }
        });
    }
}
