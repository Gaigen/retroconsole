package com.retroconsole.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Namespaced font: does not replace minecraft:default or the classic UI font. */
public final class UiTypography {
    private static final Style[] SCALED_STYLES = new Style[8];
    private static final Style[] BINDING_STYLES = new Style[8];
    static {
        for (int i = 0; i < SCALED_STYLES.length; i++) {
            SCALED_STYLES[i] = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("retroconsole", "ui_" + (i + 1)));
            BINDING_STYLES[i] = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("retroconsole", "bindings_" + (i + 1)));
        }
    }
    /** Rasterize at the actual pixel scale instead of resampling a fixed-size glyph atlas. */
    private static Style style() {
        int scale = (int)net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        return SCALED_STYLES[Math.clamp(scale, 1, SCALED_STYLES.length) - 1];
    }
    private UiTypography() {}
    /** The size-10 TTF ink sits above vanilla's nominal nine-pixel line box in 1.21.1.
     * Keep this correction local to normal button labels; Controls uses its approved size-9 font. */
    public static int buttonTextY(int y, int height) { return y + (height - 9) / 2 + 2; }

    private static String regular(String value) { return value.replace("§l", "").replace("§L", ""); }
    public static Component text(String value) { return Component.literal(regular(value)).setStyle(style().withBold(false)); }
    public static Component text(Component value) { return value.copy().withStyle(s -> s.withFont(style().getFont())); }
    public static int width(Font font, String value) { return font.width(text(value)); }
    public static String trim(Font font, String value, int max) {
        return font.getSplitter().plainHeadByWidth(regular(value), Math.max(0, max), style().withBold(false));
    }
    /** Slightly smaller font only for the Controls table; never rescale an existing glyph atlas. */
    private static Style bindingStyle() {
        int scale=(int)net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        return BINDING_STYLES[Math.clamp(scale,1,BINDING_STYLES.length)-1];
    }
    public static Component bindingText(String value) {return Component.literal(regular(value)).setStyle(bindingStyle().withBold(false));}
    public static int bindingWidth(Font font,String value) {return font.width(bindingText(value));}
    public static String bindingTrim(Font font,String value,int width) {
        return font.getSplitter().plainHeadByWidth(regular(value),Math.max(0,width),bindingStyle().withBold(false));
    }
    /** Font view for vanilla text editing: drawing AND hit-test metrics share the UI font. */
    public static Font editingFont(Font base) {
        return new Font(id -> null, false) {
            private final net.minecraft.client.StringSplitter scoped = new net.minecraft.client.StringSplitter(
                (cp, style) -> base.getSplitter().stringWidth(net.minecraft.util.FormattedCharSequence.codepoint(cp, style())));
            private net.minecraft.util.FormattedCharSequence scope(net.minecraft.util.FormattedCharSequence value) {
                return sink -> value.accept((i, style, cp) -> sink.accept(i, style.withFont(style().getFont()), cp));
            }
            @Override public int width(String v) { return base.width(text(v)); }
            @Override public int width(net.minecraft.network.chat.FormattedText v) { return net.minecraft.util.Mth.ceil(scoped.stringWidth(v)); }
            @Override public int width(net.minecraft.util.FormattedCharSequence v) { return base.width(scope(v)); }
            @Override public String plainSubstrByWidth(String v, int w) { return scoped.plainHeadByWidth(v,w,style()); }
            @Override public String plainSubstrByWidth(String v, int w, boolean reverse) { return reverse ? scoped.plainTailByWidth(v,w,style()) : plainSubstrByWidth(v,w); }
            @Override public net.minecraft.client.StringSplitter getSplitter() { return scoped; }
            @Override public java.util.List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText v,int width) {
                return net.minecraft.locale.Language.getInstance().getVisualOrder(scoped.splitLines(v,width,style()));
            }
            @Override public net.minecraft.network.chat.FormattedText substrByWidth(net.minecraft.network.chat.FormattedText v,int width) {return scoped.headByWidth(v,width,style());}
            @Override public int wordWrapHeight(String v,int width) {return 9*split(text(v),width).size();}
            @Override public int wordWrapHeight(net.minecraft.network.chat.FormattedText v,int width) {return 9*split(v,width).size();}
            @Override public void drawInBatch8xOutline(net.minecraft.util.FormattedCharSequence v,float x,float y,int c,int outline,org.joml.Matrix4f matrix,net.minecraft.client.renderer.MultiBufferSource buffers,int light) {base.drawInBatch8xOutline(scope(v),x,y,c,outline,matrix,buffers,light);}

            @Override public int drawInBatch(String v,float x,float y,int c,boolean shadow,org.joml.Matrix4f matrix,net.minecraft.client.renderer.MultiBufferSource buffers,Font.DisplayMode mode,int bg,int light) {
                return base.drawInBatch(text(v).getVisualOrderText(),x,y,c,false,matrix,buffers,mode,bg,light);
            }
            @Override public int drawInBatch(net.minecraft.util.FormattedCharSequence v,float x,float y,int c,boolean shadow,org.joml.Matrix4f matrix,net.minecraft.client.renderer.MultiBufferSource buffers,Font.DisplayMode mode,int bg,int light) {
                return base.drawInBatch(scope(v),x,y,c,false,matrix,buffers,mode,bg,light);
            }
            @Override public int drawInBatch(String v,float x,float y,int c,boolean shadow,org.joml.Matrix4f matrix,net.minecraft.client.renderer.MultiBufferSource buffers,Font.DisplayMode mode,int bg,int light,boolean bidi) {
                String shaped=bidi ? base.bidirectionalShaping(v) : v;
                return base.drawInBatch(text(shaped).getVisualOrderText(),x,y,c,false,matrix,buffers,mode,bg,light);
            }
            @Override public int drawInBatch(Component v,float x,float y,int c,boolean shadow,org.joml.Matrix4f matrix,net.minecraft.client.renderer.MultiBufferSource buffers,Font.DisplayMode mode,int bg,int light) {
                return base.drawInBatch(text(v),x,y,c,false,matrix,buffers,mode,bg,light);
            }
        };
    }
}
