package com.retroconsole.fabric;
import com.retroconsole.network.payload.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
public final class FabricNetwork {
 private FabricNetwork(){}
 public static void registerCommon(){for(NetPayload<?> d:RetroPayloads.SERVER_BOUND)register(d);}
 private static <T> void register(NetPayload<T> d){ServerPlayNetworking.registerGlobalReceiver(FabricPayload.type(d),(server,player,listener,buffer,sender)->{
  T value=d.codec().decode(buffer);
  RetroPayloads.serverHandler(d).handle(value,new FabricContext(player));
 });}
}
