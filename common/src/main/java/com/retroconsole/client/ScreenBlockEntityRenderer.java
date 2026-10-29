package com.retroconsole.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.retroconsole.block.ScreenBlock;
import com.retroconsole.block.ScreenBlockEntity;
import com.retroconsole.style.RetroTone;
import com.retroconsole.style.ScreenDesign;
import com.retroconsole.style.ScreenMount;
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

import java.util.List;

/**
 * Draws a screen: the shell around the picture and the frame inside it.
 *
 * <p>The shell comes from {@link ScreenShell}, which knows how a tile of a wall
 * is shaped; the picture is clipped to the aperture that shell leaves open, so a
 * wide bezel crops the image the same way a real CRT does instead of smearing
 * it across the frame. Only the frame is full-bright, the shell takes world
 * light.
 */
public class ScreenBlockEntityRenderer
        implements BlockEntityRenderer<ScreenBlockEntity, ScreenBlockEntityRenderer.ScreenRenderState> {

    private static final Identifier SCREENSAVER =
            Identifier.fromNamespaceAndPath("retroconsole", "textures/block/screensaver.png");

    /** Vanilla face shading so the cuboids read as solid boxes. */
    private static final float SHADE_UP = 1.0f;
    private static final float SHADE_DOWN = 0.5f;
    private static final float SHADE_X = 0.6f;
    private static final float SHADE_Z = 0.8f;

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    /** Snapshot of everything the frame needs; filled on the render thread, drawn later. */
    public static class ScreenRenderState extends BlockEntityRenderState {
        boolean valid;
        Direction facing = Direction.NORTH;
        Direction orientation = Direction.UP;
        ScreenDesign design = ScreenDesign.RETRO;
        ScreenMount mount = ScreenMount.FRONT;
        RetroTone tone = RetroTone.CREAM;
        int gridW = 1;
        int gridH = 1;
        int col;
        int row;
        @Nullable
        Identifier texture;
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
        state.design = blockState.getValue(ScreenBlock.DESIGN);
        state.mount = blockState.getValue(ScreenBlock.MOUNT);
        state.tone = blockState.getValue(ScreenBlock.TONE);
        state.gridW = Math.max(1, be.getGridWidth());
        state.gridH = Math.max(1, be.getGridHeight());
        // The shell is authored with x to the viewer's left and y up from the
        // bottom, while the entity indexes cells from the top left corner.
        int cellX = Math.max(0, Math.min(state.gridW - 1, be.getXIndex()));
        int cellY = Math.max(0, Math.min(state.gridH - 1, be.getYIndex()));
        state.col = state.gridW - 1 - cellX;
        state.row = state.gridH - 1 - cellY;

        var entry = be.getConsoleId() != null
                ? ClientConsoles.getScreen(be.getConsoleId()) : null;
        state.texture = (entry != null) ? entry.id() : SCREENSAVER;
        // The in-world picture is drawn at whatever size the player's distance and
        // view angle give it — almost never a whole multiple of the texture. Nearest
        // would then drop rows and columns at random (text turns to mush), so smooth it.
        if (entry != null) entry.setLinear(true);
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

        List<ScreenShell.Box> boxes = ScreenShell.boxes(
                state.design, state.mount, state.gridW, state.gridH, state.col, state.row);
        if (!boxes.isEmpty()) {
            int light = state.lightCoords;
            // The shell is coloured per vertex on a blank texture: palette cells are
            // flat, and sampling them from the atlas made thin strips glow at
            // grazing angles as the mips mixed in the cells around them.
            float[] body = state.tone.bodyChannels();
            collector.submitCustomGeometry(ps, RenderTypes.entityCutout(ChassisPalette.PLAIN),
                    (pose, vc) -> {
                        for (ScreenShell.Box box : boxes) {
                            boolean shell = box.mat() == ScreenShell.Mat.BODY;
                            box(vc, pose, box, shell ? body : ChassisPalette.channels(box.mat().rgb()),
                                    light);
                        }
                    });
        }

        ScreenShell.Aperture ap = ScreenShell.aperture(state.design, state.gridW, state.gridH);
        float tileX = state.col * 16.0f;
        float tileY = state.row * 16.0f;
        float x0 = Math.max(ap.left(), tileX);
        float x1 = Math.min(ap.right(), tileX + 16.0f);
        float y0 = Math.max(ap.bottom(), tileY);
        float y1 = Math.min(ap.top(), tileY + 16.0f);
        if (x1 - x0 > 0.001f && y1 - y0 > 0.001f) {
            float spanU = ap.right() - ap.left();
            float spanV = ap.top() - ap.bottom();
            // u counts down the model x axis (see the render contracts), so the
            // left edge of the picture is the high-x edge of the tile.
            float uLeft = (ap.right() - x1) / spanU;
            float uRight = (ap.right() - x0) / spanU;
            float vTop = 1.0f - (y1 - ap.bottom()) / spanV;
            float vBottom = 1.0f - (y0 - ap.bottom()) / spanV;

            // Shell boxes are built per tile, so the picture has to be measured
            // from the tile's own corner too; wall coordinates would throw it a
            // whole tile away on every tile but the first. The quad is also grown
            // a hair where the shell is in front of it, so its edge hides under
            // the lip instead of fighting the lip's silhouette for the same pixel.
            float grow = 0.05f;
            float gx0 = Math.max(tileX, x0 - grow);
            float gx1 = Math.min(tileX + 16.0f, x1 + grow);
            float gy0 = Math.max(tileY, y0 - grow);
            float gy1 = Math.min(tileY + 16.0f, y1 + grow);

            float lxLeft = 0.5f - (gx1 - tileX) / 16.0f;
            float lxRight = 0.5f - (gx0 - tileX) / 16.0f;
            float ly0 = (gy0 - tileY) / 16.0f - 0.5f;
            float ly1 = (gy1 - tileY) / 16.0f - 0.5f;
            float z = 0.5f - (ap.videoZ() + state.mount.shift(state.design)) / 16.0f;
            RenderType video = RenderTypes.entityCutout(state.texture);

            collector.submitCustomGeometry(ps, video, (pose, vc) -> {
                int light = 0xF000F0;
                vert(vc, pose, lxLeft, ly0, z, uLeft, vBottom, light, 1, 1, 1, 0, 0, 1);
                vert(vc, pose, lxRight, ly0, z, uRight, vBottom, light, 1, 1, 1, 0, 0, 1);
                vert(vc, pose, lxRight, ly1, z, uRight, vTop, light, 1, 1, 1, 0, 0, 1);
                vert(vc, pose, lxLeft, ly1, z, uLeft, vTop, light, 1, 1, 1, 0, 0, 1);
            });
        }

        ps.popPose();
    }

    /** Emits a solid axis-aligned box with per-face vanilla shading. */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, ScreenShell.Box b,
                            float[] tint, int light) {
        // Model space puts +X to the viewer's left and +Z into the body; the pose
        // has already turned the block 180°, so both axes flip on the way in.
        float x0 = 0.5f - b.x1() / 16.0f;
        float x1 = 0.5f - b.x0() / 16.0f;
        float y0 = b.y0() / 16.0f - 0.5f;
        float y1 = b.y1() / 16.0f - 0.5f;
        float zn = 0.5f - b.z1() / 16.0f;
        float zf = 0.5f - b.z0() / 16.0f;
        // One flat texture, so the whole face maps to it.
        float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;

        // Counter-clockwise seen from outside, so back faces can be culled.
        face(vc, pose, tint, light, SHADE_UP, u0, v0, u1, v1, 0, 1, 0,
                x0, y1, zf, x1, y1, zf, x1, y1, zn, x0, y1, zn);
        face(vc, pose, tint, light, SHADE_DOWN, u0, v0, u1, v1, 0, -1, 0,
                x0, y0, zn, x1, y0, zn, x1, y0, zf, x0, y0, zf);
        if (!b.open()) {
            face(vc, pose, tint, light, SHADE_Z, u0, v0, u1, v1, 0, 0, 1,
                    x0, y0, zf, x1, y0, zf, x1, y1, zf, x0, y1, zf);
        }
        face(vc, pose, tint, light, SHADE_Z, u0, v0, u1, v1, 0, 0, -1,
                x1, y0, zn, x0, y0, zn, x0, y1, zn, x1, y1, zn);
        face(vc, pose, tint, light, SHADE_X, u0, v0, u1, v1, 1, 0, 0,
                x1, y0, zf, x1, y0, zn, x1, y1, zn, x1, y1, zf);
        face(vc, pose, tint, light, SHADE_X, u0, v0, u1, v1, -1, 0, 0,
                x0, y0, zn, x0, y0, zf, x0, y1, zf, x0, y1, zn);
    }

    private static void face(VertexConsumer vc, PoseStack.Pose pose, float[] tint, int light,
                             float shade, float u0, float v0, float u1, float v1,
                             float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        float r = tint[0] * shade;
        float g = tint[1] * shade;
        float b = tint[2] * shade;
        vert(vc, pose, ax, ay, az, u0, v1, light, r, g, b, nx, ny, nz);
        vert(vc, pose, bx, by, bz, u1, v1, light, r, g, b, nx, ny, nz);
        vert(vc, pose, cx, cy, cz, u1, v0, light, r, g, b, nx, ny, nz);
        vert(vc, pose, dx, dy, dz, u0, v0, light, r, g, b, nx, ny, nz);
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                             float u, float v, int light,
                             float r, float g, float b, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, 1.0f)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(nx, ny, nz);
    }
}
