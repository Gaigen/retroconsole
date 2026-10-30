package com.retroconsole.platform;
import com.retroconsole.network.payload.NetContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.world.entity.player.Player;
public record ForgeContext(NetworkEvent.Context context) implements NetContext {
 public Player player(){return context.getSender();}
 public void enqueue(Runnable task){context.enqueueWork(task);}
}
