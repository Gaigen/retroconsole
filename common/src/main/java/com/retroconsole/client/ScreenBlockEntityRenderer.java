package com.retroconsole.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.retroconsole.block.ScreenBlock;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Рисует кадр на экранных блоках. Вся геометрия группы приходит с сервера:
 * xIndex/yIndex/width/height/consoleId — из BE, FACING/ORIENTATION — из блокстейта.
 */
public class ScreenBlockEntityRenderer
        implements BlockEntityRenderer<ScreenBlockEntity, ScreenBlockEntityRenderer.ScreenRenderState> {

    private static final Identifier SCREENSAVER =
            Identifier.fromNamespaceAndPath("retroconsole", "textures/block/screensaver.png");

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    /** Snapshot of everything the frame needs; filled on the render thread, drawn later. */
    public static class ScreenRenderState extends BlockEntityRenderState {
        boolean valid;
        Direction facing = Direction.NORTH;
        Direction orientation = Direction.UP;
        Identifier texture = SCREENSAVER;
        float u0, u1, v0, v1;
    }

    @Override
    public ScreenRenderState createRenderState() {
        return new ScreenRenderState();
    }

    @Override
    public void extractRenderState(ScreenBlockEntity be, ScreenRenderState state, float pt,
                                   Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, pt, cameraPosition, breakProgress);
        BlockState blockState = be.getBlockState();
        if (!(blockState.getBlock() instanceof ScreenBlock)) {
            state.valid = false;
            return;
        }
        state.valid = true;
        state.facing = blockState.getValue(ScreenBlock.FACING);
        state.orientation = blockState.getValue(ScreenBlock.ORIENTATION);

        var entry = be.getConsoleId() != null
                ? ClientConsoles.getScreen(be.getConsoleId()) : null;
        state.texture = (entry != null) ? entry.id() : SCREENSAVER;
        // The in-world screen is drawn at whatever size the player's distance and view
        // angle give it — almost never a whole multiple of the texture. Nearest would
        // then drop rows and columns at random (text turns to mush), so smooth it.
        if (entry != null) entry.setLinear(true);

        int gridW = Math.max(1, be.getGridWidth());
        int gridH = Math.max(1, be.getGridHeight());
        state.u0 = (float) be.getXIndex() / gridW;
        state.u1 = (float) (be.getXIndex() + 1) / gridW;
        state.v0 = (float) be.getYIndex() / gridH;
        state.v1 = (float) (be.getYIndex() + 1) / gridH;
    }

    @Override
    public void submit(ScreenRenderState state, PoseStack ps,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.valid) return;

        ps.pushPose();
        ps.translate(0.5, 0.5, 0.5);
        ps.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        if (state.orientation == Direction.UP) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));
        } else if (state.orientation == Direction.DOWN) {
            ps.mulPose(Axis.XP.rotationDegrees(90));
        }
        ps.translate(0, 0, 0.501);

        collector.submitCustomGeometry(ps, RenderTypes.entityCutout(state.texture),
                (pose, vc) -> {
                    float s = 0.5f;
                    int L = 0xF000F0;
                    vertex(vc, pose, -s, -s, state.u0, state.v1, L);
                    vertex(vc, pose, s, -s, state.u1, state.v1, L);
                    vertex(vc, pose, s, s, state.u1, state.v0, L);
                    vertex(vc, pose, -s, s, state.u0, state.v0, L);
                });

        ps.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose,
                               float x, float y, float u, float v, int light) {
        vc.addVertex(pose, x, y, 0)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(0, 0, 1);
    }
}
