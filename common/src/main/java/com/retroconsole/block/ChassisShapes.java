package com.retroconsole.block;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.EnumMap;
import java.util.Map;
/** 1.21.1 equivalent of the later Shapes.rotateHorizontal API. */
public final class ChassisShapes {
 private ChassisShapes() {}
 public static Map<Direction,VoxelShape> horizontal(VoxelShape north) {
  Map<Direction,VoxelShape> out=new EnumMap<>(Direction.class);out.put(Direction.NORTH,north);
  VoxelShape shape=north;
  for(Direction facing:new Direction[]{Direction.EAST,Direction.SOUTH,Direction.WEST}) {
   VoxelShape[] next={Shapes.empty()};
   shape.forAllBoxes((x0,y0,z0,x1,y1,z1)->next[0]=Shapes.or(next[0],Shapes.box(1-z1,y0,x0,1-z0,y1,x1)));
   shape=next[0].optimize();out.put(facing,shape);
  }
  return out;
 }
}
