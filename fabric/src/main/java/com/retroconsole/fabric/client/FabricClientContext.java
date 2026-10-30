package com.retroconsole.fabric.client;
import com.retroconsole.network.payload.NetContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
public record FabricClientContext(Minecraft client) implements NetContext {
 public Player player(){return client.player;}
 public void enqueue(Runnable task){client.execute(task);}
}
