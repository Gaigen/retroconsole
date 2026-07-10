package com.retroconsole.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.retroconsole.block.ScreenBlock;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * Рисует кадр на экранных блоках. Вся геометрия группы приходит с сервера:
 * xIndex/yIndex/width/height — из BE, FACING/ORIENTATION — из блокстейта.
 */
public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {

    private static final ResourceLocation SCREENSAVER =
            ResourceLocation.fromNamespaceAndPath("retroconsole", "textures/block/screensaver.png");

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ScreenBlockEntity be, float pt, PoseStack ps,
                       MultiBufferSource buf, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof ScreenBlock)) return;
        Direction facing = state.getValue(ScreenBlock.FACING);
        Direction orientation = state.getValue(ScreenBlock.ORIENTATION);

        BlockPos consolePos = be.getConsolePos();
        var entry = (consolePos != null && !consolePos.equals(BlockPos.ZERO))
                ? ClientConsoles.getScreen(consolePos) : null;
        ResourceLocation tex = (entry != null) ? entry.id() : SCREENSAVER;

        int gridW = Math.max(1, be.getGridWidth());
        int gridH = Math.max(1, be.getGridHeight());
        float u0 = (float) be.getXIndex() / gridW;
        float u1 = (float) (be.getXIndex() + 1) / gridW;
        float v0 = (float) be.getYIndex() / gridH;
        float v1 = (float) (be.getYIndex() + 1) / gridH;

        ps.pushPose();
        ps.translate(0.5, 0.5, 0.5);
        ps.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        if (orientation == Direction.UP) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));
        } else if (orientation == Direction.DOWN) {
            ps.mulPose(Axis.XP.rotationDegrees(90));
        }
        ps.translate(0, 0, 0.501);

        Matrix4f m = ps.last().pose();
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        float s = 0.5f;
        int L = 0xF000F0;

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
