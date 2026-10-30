package com.retroconsole.fabric.client;
import com.retroconsole.client.ClientPacketHandlers;
import com.retroconsole.fabric.FabricPayload;
import com.retroconsole.network.payload.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
public final class FabricClientNetwork {
 private FabricClientNetwork(){}
 public static void register(){for(NetPayload<?> d:RetroPayloads.CLIENT_BOUND)register(d);}
 private static <T> void register(NetPayload<T> d){ClientPlayNetworking.registerGlobalReceiver(FabricPayload.type(d),(client,listener,buffer,sender)->{
  T value=d.codec().decode(buffer);
  ClientPacketHandlers.handler(d).handle(value,new FabricClientContext(client));
 });}
}
