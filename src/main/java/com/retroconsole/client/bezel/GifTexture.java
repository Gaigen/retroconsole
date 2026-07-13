package com.retroconsole.client.bezel;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Animated GIF as a ticking Minecraft texture.
 * Кадры КОМПОЗИТЯТСЯ на полный логический холст GIF:
 * частичные кадры, смещения и disposal-методы обрабатываются корректно.
 */
public final class GifTexture extends AbstractTexture {

    private final List<NativeImage> frames = new ArrayList<>();
    private final List<Integer> delaysMs = new ArrayList<>();
    private int frameIndex;
    private long lastAdvanceMs;
    private final ResourceLocation id;
    private int width = 1;
    private int height = 1;

    public GifTexture(ResourceLocation id, Path file) throws IOException {
        this.id = id;
        decode(file);
        if (!frames.isEmpty()) {
            width = frames.get(0).getWidth();
            height = frames.get(0).getHeight();
            TextureUtil.prepareImage(getId(), width, height);
            upload(frames.get(0));
        }
    }

    public int width() { return width; }

    public int height() { return height; }

    private void decode(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(Files.newInputStream(file))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("No GIF reader");
            ImageReader reader = readers.next();
            reader.setInput(in);
            int count = reader.getNumImages(true);

            // Размер ХОЛСТА гифки — из LogicalScreenDescriptor, а не первого кадра
            int canvasW = 0, canvasH = 0;
            try {
                Node root = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if ("LogicalScreenDescriptor".equals(n.getNodeName())) {
                        canvasW = intAttr(n, "logicalScreenWidth");
                        canvasH = intAttr(n, "logicalScreenHeight");
                    }
                }
            } catch (Exception ignored) {}

            BufferedImage canvas = null;
            Graphics2D g = null;

            for (int i = 0; i < count; i++) {
                BufferedImage raw = reader.read(i);

                int fx = 0, fy = 0;
                String disposal = "none";
                int delayMs = 100;
                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
                    for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if ("ImageDescriptor".equals(n.getNodeName())) {
                            fx = intAttr(n, "imageLeftPosition");
                            fy = intAttr(n, "imageTopPosition");
                        } else if ("GraphicControlExtension".equals(n.getNodeName())) {
                            disposal = strAttr(n, "disposalMethod", "none");
                            int centis = intAttr(n, "delayTime");
                            delayMs = centis <= 1 ? 100 : centis * 10;
                        }
                    }
                } catch (Exception ignored) {}

                if (canvas == null) {
                    if (canvasW <= 0) canvasW = fx + raw.getWidth();
                    if (canvasH <= 0) canvasH = fy + raw.getHeight();
                    canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                    g = canvas.createGraphics();
                }

                // Снимок «до» — нужен для disposal restoreToPrevious
                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;

                g.drawImage(raw, fx, fy, null);          // наложить кадр с его смещением
                frames.add(toNative(canvas));            // снапшот СКОМПОЗИЧЕННОГО холста
                delaysMs.add(Math.max(20, delayMs));

                // Disposal применяется ПОСЛЕ показа кадра
                switch (disposal) {
                    case "restoreToBackgroundColor" -> {
                        g.setComposite(AlphaComposite.Clear);
                        g.fillRect(fx, fy, raw.getWidth(), raw.getHeight());
                        g.setComposite(AlphaComposite.SrcOver);
                    }
                    case "restoreToPrevious" -> {
                        g.setComposite(AlphaComposite.Src);
                        g.drawImage(before, 0, 0, null);
                        g.setComposite(AlphaComposite.SrcOver);
                    }
                    default -> {} // none / doNotDispose — холст остаётся
                }
            }
            if (g != null) g.dispose();
            reader.dispose();
        }
    }

    private static int intAttr(Node n, String name) {
        try {
            Node a = n.getAttributes().getNamedItem(name);
            return a == null ? 0 : Integer.parseInt(a.getNodeValue());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String strAttr(Node n, String name, String def) {
        Node a = n.getAttributes().getNamedItem(name);
        return a == null ? def : a.getNodeValue();
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static NativeImage toNative(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = bi.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int gg = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (gg << 8) | r);
            }
        }
        return img;
    }

    public void tick(long nowMs) {
        if (frames.size() <= 1) return;
        int delay = delaysMs.get(frameIndex);
        if (nowMs - lastAdvanceMs < delay) return;
        lastAdvanceMs = nowMs;
        frameIndex = (frameIndex + 1) % frames.size();
        upload(frames.get(frameIndex));
    }

    private void upload(NativeImage img) {
        this.bind();
        img.upload(0, 0, 0, false);
    }

    public ResourceLocation location() {
        return id;
    }

    @Override
    public void load(ResourceManager manager) {
        // loaded eagerly in constructor
    }

    public void closeFrames() {
        for (NativeImage f : frames) f.close();
        frames.clear();
    }
}