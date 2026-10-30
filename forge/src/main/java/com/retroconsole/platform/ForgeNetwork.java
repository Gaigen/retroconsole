package com.retroconsole.platform;
import com.retroconsole.client.ClientPacketHandlers;
import com.retroconsole.network.payload.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import io.netty.handler.codec.DecoderException;
import java.util.function.Supplier;
public final class ForgeNetwork {
 private static final String VERSION="1.20.1-1";
 public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation("retroconsole","main"),()->VERSION,VERSION::equals,VERSION::equals);
 public record ToClient(NetPayload<?> definition,Object value){}
 public record ToServer(NetPayload<?> definition,Object value){}
 private ForgeNetwork(){}
 public static void registerPackets(){
  CHANNEL.messageBuilder(ToClient.class,0,NetworkDirection.PLAY_TO_CLIENT)
   .encoder((m,b)->write(m.definition(),m.value(),b,RetroPayloads.CLIENT_BOUND))
   .decoder(b->{var v=read(b,RetroPayloads.CLIENT_BOUND);return new ToClient(v.definition(),v.value());})
   .consumerNetworkThread((m,c)->{handleClient(m,c);c.get().setPacketHandled(true);}).add();
  CHANNEL.messageBuilder(ToServer.class,1,NetworkDirection.PLAY_TO_SERVER)
   .encoder((m,b)->write(m.definition(),m.value(),b,RetroPayloads.SERVER_BOUND))
   .decoder(b->{var v=read(b,RetroPayloads.SERVER_BOUND);return new ToServer(v.definition(),v.value());})
   .consumerNetworkThread((m,c)->{handleServer(m,c);c.get().setPacketHandled(true);}).add();
 }
 private record Decoded(NetPayload<?> definition,Object value){}
 @SuppressWarnings({"rawtypes","unchecked"})
 private static void write(NetPayload d,Object value,FriendlyByteBuf b,java.util.List<NetPayload<?>> table){
  int id=table.indexOf(d);if(id<0)throw new IllegalArgumentException("Wrong packet direction");b.writeVarInt(id);d.codec().encode(b,value);
 }
 private static Decoded read(FriendlyByteBuf b,java.util.List<NetPayload<?>> table){
  int id=b.readVarInt();if(id<0||id>=table.size())throw new DecoderException("Unknown RetroConsole packet: "+id);
  NetPayload<?> d=table.get(id);return new Decoded(d,d.codec().decode(b));
 }
 @SuppressWarnings({"rawtypes","unchecked"})
 private static void handleServer(ToServer m,Supplier<NetworkEvent.Context> ctx){RetroPayloads.serverHandler((NetPayload)m.definition()).handle(m.value(),new ForgeContext(ctx.get()));}
 @SuppressWarnings({"rawtypes","unchecked"})
 private static void handleClient(ToClient m,Supplier<NetworkEvent.Context> ctx){ClientPacketHandlers.handler((NetPayload)m.definition()).handle(m.value(),new ForgeContext(ctx.get()));}
}
