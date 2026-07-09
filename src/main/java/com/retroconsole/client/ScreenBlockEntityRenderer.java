package com.retroconsole.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix4f;

/**
 * Renders the game frame on screen blocks.
 * Supports multi-block screens: each block renders its UV portion.
 */
public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    private static final ResourceLocation SCREENSAVER =
            ResourceLocation.fromNamespaceAndPath("retroconsole", "textures/block/screensaver.png");

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ScreenBlockEntity be, float pt, PoseStack ps,
                       MultiBufferSource buf, int light, int overlay) {
        BlockPos consolePos = be.getConsolePos();

        // Get the screen entry for this console
        var entry = (consolePos != null && !consolePos.equals(BlockPos.ZERO))
                ? ClientConsoles.getScreen(consolePos) : null;

        ResourceLocation tex = (entry != null) ? entry.id() : SCREENSAVER;

        // Calculate UV for multi-block
        int[] grid = ClientConsoles.getGridInfo(be);
        int gridX = grid[0], gridY = grid[1];
        int gridW = grid[2], gridH = grid[3];

        // Determine facing from the console block
        Direction facing = Direction.NORTH;
        if (consolePos != null && be.getLevel() != null) {
            var consoleState = be.getLevel().getBlockState(consolePos);
            if (consoleState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                facing = consoleState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
        }

        // Mirror U when "right for the viewer" maps to decreasing world coordinate
        // (NORTH: -X, EAST: -Z). SOUTH/WEST grow with gridX and need no mirror.
        boolean mirrorU = facing == Direction.NORTH || facing == Direction.EAST;
        float u0, u1, v0, v1;
        if (mirrorU) {
            u0 = 1.0f - (float) (gridX + 1) / gridW;
            u1 = 1.0f - (float) gridX / gridW;
        } else {
            u0 = (float) gridX / gridW;
            u1 = (float) (gridX + 1) / gridW;
        }
        v0 = (float) gridY / gridH;
        v1 = (float) (gridY + 1) / gridH;

        // Render quad on front face of block
        ps.pushPose();
        ps.translate(0.5, 0.5, 0.5);
        ps.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        ps.translate(0, 0, 0.501);

        Matrix4f m = ps.last().pose();
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        float s = 0.5f;
        int L = 0xF000F0; // Fullbright

        vertex(vc, m, -s, -s, u0, v1, L);
        vertex(vc, m,  s, -s, u1, v1, L);
        vertex(vc, m,  s,  s, u1, v0, L);
        vertex(vc, m, -s,  s, u0, v0, L);

        ps.popPose();
    }

    private void vertex(VertexConsumer vc, Matrix4f m,
                         float x, float y, float u, float v, int light) {
        vc.addVertex(m, x, y, 0)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(0, 0, 1);
    }
}
