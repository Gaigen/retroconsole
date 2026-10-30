package com.retroconsole.fabric;
import com.retroconsole.network.payload.NetPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
public final class FabricPayload {
 private FabricPayload(){}
 public static ResourceLocation type(NetPayload<?> definition){return new ResourceLocation("retroconsole",definition.id());}
 @SuppressWarnings({"unchecked","rawtypes"})
 public static FriendlyByteBuf encode(NetPayload definition,Object value){FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());definition.codec().encode(b,value);return b;}
}
