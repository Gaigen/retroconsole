package com.retroconsole.client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Shared flat slider with native keyboard, mouse, narration and drag semantics. */
public class UiSlider extends AbstractSliderButton {
    private final DoubleConsumer setter;
    private final DoubleFunction<Component> label;
    public UiSlider(int x,int y,int w,int h,double value,DoubleConsumer setter,DoubleFunction<Component> label) {
        super(x,y,w,h,Component.empty(),Mth.clamp(value,0,1)); this.setter=setter; this.label=label; updateMessage();
    }
    @Override protected void updateMessage() { if(label!=null)setMessage(UiTypography.text(label.apply(value))); }
    @Override protected void applyValue() { setter.accept(value); }
    @Override public void renderWidget(GuiGraphics g,int mx,int my,float pt) {
        paint(g,this,value); 
    }
    public static void paint(GuiGraphics g,AbstractSliderButton widget,double value) {
        int x=widget.getX(),y=widget.getY(),w=widget.getWidth(),h=widget.getHeight();
        g.fill(x,y,x+w,y+h,widget.isFocused()?0xFF818FBF:0xFF3C4658);
        g.fill(x+1,y+1,x+w-1,y+h-1,0xFF1B222E);
        var font=Minecraft.getInstance().font;
        var text=UiTypography.text(widget.getMessage());
        g.drawString(font,text,x+(w-font.width(text))/2,y+(h<22?2:4),widget.active?0xFFE8EAF0:0xFF8991A0,false);
        int trackY=y+h-5, knob=x+5+(int)((w-10)*value);
        g.fill(x+5,trackY,x+w-5,trackY+1,0xFF586174);
        g.fill(x+5,trackY,knob,trackY+1,0xFFB8C3FF);
        g.fill(knob-2,trackY-2,knob+2,trackY+3,0xFFB8C3FF);
    }
}
