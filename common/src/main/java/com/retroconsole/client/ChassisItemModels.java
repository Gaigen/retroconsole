package com.retroconsole.client;
import com.retroconsole.reg.ModBlocks;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
/** 1.21.1 predicates replacing 26's block_state item-model select nodes. */
public final class ChassisItemModels {
 private ChassisItemModels() {}
 public static void register() {
  ItemProperties.register(ModBlocks.RETRO_CONSOLE_ITEM,ResourceLocation.fromNamespaceAndPath("retroconsole","tone"),
   (stack,level,entity,seed)->value(stack,"tone",new String[]{"cream","graphite","lavender"}));
  ItemProperties.register(ModBlocks.SCREEN_ITEM,ResourceLocation.fromNamespaceAndPath("retroconsole","design"),
   (stack,level,entity,seed)->value(stack,"design",new String[]{"retro","studio","wall","edge","solid"}));
 }
 private static float value(net.minecraft.world.item.ItemStack stack,String key,String[] values) {
  var state=stack.get(DataComponents.BLOCK_STATE);if(state==null)return 0;
  String actual=state.properties().get(key);for(int i=1;i<values.length;i++)if(values[i].equals(actual))return (float)i/(values.length-1);return 0;
 }
}
