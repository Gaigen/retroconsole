package com.retroconsole.fabric;
import com.retroconsole.network.payload.NetContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
public record FabricContext(ServerPlayer sender) implements NetContext {
 public Player player(){return sender;}
 public void enqueue(Runnable task){sender.server.execute(task);}
}
