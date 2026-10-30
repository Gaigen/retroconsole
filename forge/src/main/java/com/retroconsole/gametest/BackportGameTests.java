package com.retroconsole.gametest;

import com.retroconsole.block.*;
import com.retroconsole.item.GamepadItem;
import com.retroconsole.reg.ModBlocks;
import com.retroconsole.reg.ModItems;
import com.retroconsole.server.ServerConsoles;
import com.retroconsole.style.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** 1.21.1 API tests of the appearance backport. Never loads a native core. */
@GameTestHolder("retroconsole")
@PrefixGameTestTemplate(false)
public final class BackportGameTests {
 private BackportGameTests() {}
 private static CompoundTag consoleTag(UUID id,UUID owner) {
  CompoundTag tag=new CompoundTag();tag.putUUID("ConsoleId",id);tag.putUUID("OwnerId",owner);
  tag.putString("RomId","nes/backport.nes");tag.putString("CoreName","backport-core");return tag;
 }
 private static void checkConsole(GameTestHelper h,RetroConsoleBlockEntity be,UUID id,UUID owner) {
  h.assertTrue(id.equals(be.getConsoleId())&&owner.equals(be.getOwnerId())&&"nes/backport.nes".equals(be.getRomId())&&"backport-core".equals(be.getCoreName()),"Appearance or NBT changed console game identity");
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportConsoleNbt(GameTestHelper h) {
  var pos=new BlockPos(1,2,1);h.setBlock(pos,ModBlocks.RETRO_CONSOLE);RetroConsoleBlockEntity be=(RetroConsoleBlockEntity)h.getBlockEntity(pos);
  UUID id=UUID.randomUUID(),owner=UUID.randomUUID();be.load(consoleTag(id,owner));
  var copy=new RetroConsoleBlockEntity(be.getBlockPos(),be.getBlockState());copy.load(be.saveWithoutMetadata());checkConsole(h,copy,id,owner);
  copy.load(be.getUpdateTag());checkConsole(h,copy,id,owner);h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportAppearanceKeepsConsole(GameTestHelper h) {
  var relative=new BlockPos(1,2,1);h.setBlock(relative,ModBlocks.RETRO_CONSOLE);RetroConsoleBlockEntity be=(RetroConsoleBlockEntity)h.getBlockEntity(relative);
  UUID id=UUID.randomUUID(),owner=UUID.randomUUID();be.load(consoleTag(id,owner));
  for(var style:ConsoleStyle.values())for(var visual:ConsoleState.values())for(var tone:RetroTone.values()) {
   var state=be.getBlockState().setValue(RetroConsoleBlock.STYLE,style).setValue(RetroConsoleBlock.CONSOLE_STATE,visual).setValue(RetroConsoleBlock.TONE,tone);
   h.getLevel().setBlock(be.getBlockPos(),state,Block.UPDATE_ALL);
   h.assertTrue(h.getLevel().getBlockEntity(be.getBlockPos())==be&&!be.isRemoved(),"Cosmetic update removed/replaced BE");checkConsole(h,be,id,owner);
  }
  h.assertTrue(!ServerConsoles.hasEmulator(id)&&!ServerConsoles.isBooting(id),"Appearance started a runtime");h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportGamepadLookKeepsLink(GameTestHelper h) {
  ItemStack pad=new ItemStack(ModItems.GAMEPAD);UUID id=UUID.randomUUID();BlockPos pos=new BlockPos(2,3,4);GamepadItem.setLinkedConsole(pad,id,pos);
  for(var design:PadDesign.values())for(var tone:RetroTone.values()) {
   GamepadItem.setLook(pad,design,tone);
   h.assertTrue(GamepadItem.designOf(pad)==design&&GamepadItem.toneOf(pad)==tone,"1.21.1 custom_model_data appearance mismatch");
   h.assertTrue(id.equals(GamepadItem.getLinkedConsoleId(pad))&&pos.equals(GamepadItem.getLinkedConsolePos(pad)),"Restyle erased gamepad link");
   var copied=pad.copy();h.assertTrue(GamepadItem.designOf(copied)==design&&id.equals(GamepadItem.getLinkedConsoleId(copied)),"Item copy lost look or link");
  }
  h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportUnlinkPreservesOtherNbt(GameTestHelper h) {
  var pad=new ItemStack(ModItems.GAMEPAD);
  GamepadItem.setLook(pad,PadDesign.values()[1],RetroTone.GRAPHITE);
  pad.getOrCreateTag().putString("ThirdPartyTag","keep-me");
  GamepadItem.setLinkedConsole(pad,UUID.randomUUID(),new BlockPos(-7,25,41));
  GamepadItem.setLinkedConsole(pad,null,null);
  h.assertTrue(GamepadItem.getLinkedConsoleId(pad)==null&&GamepadItem.getLinkedConsolePos(pad)==null,"Unlink left stale identity");
  h.assertTrue(GamepadItem.designOf(pad)==PadDesign.values()[1]&&GamepadItem.toneOf(pad)==RetroTone.GRAPHITE&&"keep-me".equals(pad.getTag().getString("ThirdPartyTag")),"Unlink erased appearance or unrelated NBT");
  h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportRecipesAndLoot(GameTestHelper h) {
  for(String id:new String[]{"retro_console","screen","gamepad"}) {
   h.assertTrue(h.getLevel().getRecipeManager().byKey(new net.minecraft.resources.ResourceLocation("retroconsole",id)).isPresent(),"Missing recipe: "+id);
  }
  var pos=new BlockPos(1,2,1);
  var state=ModBlocks.RETRO_CONSOLE.defaultBlockState().setValue(RetroConsoleBlock.STYLE,ConsoleStyle.HIFI).setValue(RetroConsoleBlock.TONE,RetroTone.GRAPHITE);
  h.setBlock(pos,state);
  var drops=Block.getDrops(state,h.getLevel(),h.absolutePos(pos),h.getBlockEntity(pos));
  h.assertTrue(drops.size()==1&&drops.get(0).is(ModBlocks.RETRO_CONSOLE_ITEM),"Console loot missing");
  var finish=drops.get(0).getTag().getCompound("BlockStateTag");
  h.assertTrue("hifi".equals(finish.getString("style"))&&"graphite".equals(finish.getString("tone")),"1.20.1 loot lost block state");
  var screen=ModBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.DESIGN,ScreenDesign.STUDIO).setValue(ScreenBlock.MOUNT,ScreenMount.BACK).setValue(ScreenBlock.TONE,RetroTone.LAVENDER);
  h.setBlock(pos,screen);
  drops=Block.getDrops(screen,h.getLevel(),h.absolutePos(pos),h.getBlockEntity(pos));
  h.assertTrue(drops.size()==1&&drops.get(0).is(ModBlocks.SCREEN_ITEM),"Screen loot missing");
  finish=drops.get(0).getTag().getCompound("BlockStateTag");
  h.assertTrue("studio".equals(finish.getString("design"))&&"back".equals(finish.getString("mount"))&&"lavender".equals(finish.getString("tone")),"1.20.1 screen loot lost block state");
  h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportPlacementKeepsFinish(GameTestHelper h) {
  var support=new BlockPos(1,1,1);h.setBlock(support,Blocks.STONE);var player=h.makeMockPlayer();
  var stack=new ItemStack(ModBlocks.RETRO_CONSOLE_ITEM);var stateTag=new CompoundTag();stateTag.putString("style","hifi");stateTag.putString("tone","graphite");stack.getOrCreateTag().put("BlockStateTag",stateTag);
  player.setItemInHand(InteractionHand.MAIN_HAND,stack);var absolute=h.absolutePos(support);var hit=new BlockHitResult(Vec3.atCenterOf(absolute).add(0,0.5,0),Direction.UP,absolute,false);
  var result=((BlockItem)stack.getItem()).place(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,hit));var placed=h.getLevel().getBlockState(absolute.above());
  h.assertTrue(result.consumesAction()&&placed.is(ModBlocks.RETRO_CONSOLE)&&placed.getValue(RetroConsoleBlock.STYLE)==ConsoleStyle.HIFI&&placed.getValue(RetroConsoleBlock.TONE)==RetroTone.GRAPHITE,"Vanilla placement lost style/tone");h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportMixedScreenRemainsAllowed(GameTestHelper h) {
  var a=new BlockPos(0,1,1);var b=new BlockPos(1,1,1);var c=new BlockPos(0,1,0);h.setBlock(c,ModBlocks.RETRO_CONSOLE);
  h.setBlock(a,ModBlocks.SCREEN.defaultBlockState());h.setBlock(b,ModBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.DESIGN,ScreenDesign.STUDIO).setValue(ScreenBlock.MOUNT,ScreenMount.BACK).setValue(ScreenBlock.TONE,RetroTone.LAVENDER));
  ScreenMultiblocks.rebuildAround(h.getLevel(),h.absolutePos(a));ScreenBlockEntity first=(ScreenBlockEntity)h.getBlockEntity(a),second=(ScreenBlockEntity)h.getBlockEntity(b);RetroConsoleBlockEntity console=(RetroConsoleBlockEntity)h.getBlockEntity(c);
  h.assertTrue(first.getGridWidth()==2&&second.getGridWidth()==2&&first.getGridHeight()==1,"Mixed appearance unexpectedly split a panel");
  h.assertTrue(console.getConsoleId().equals(first.getConsoleId())&&first.getConsoleId().equals(second.getConsoleId()),"Screen rectangle lost console link");
  h.assertTrue(h.getLevel().getBlockState(h.absolutePos(b)).getValue(ScreenBlock.DESIGN)==ScreenDesign.STUDIO,"Assembly overwrote mixed appearance");h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportLScreenSplitsRectangles(GameTestHelper h) {
  var a=new BlockPos(0,1,1);var b=new BlockPos(1,1,1);var c=new BlockPos(0,2,1);
  h.setBlock(a,ModBlocks.SCREEN);h.setBlock(b,ModBlocks.SCREEN);h.setBlock(c,ModBlocks.SCREEN);ScreenMultiblocks.rebuildAround(h.getLevel(),h.absolutePos(a));
  ScreenBlockEntity first=(ScreenBlockEntity)h.getBlockEntity(a),second=(ScreenBlockEntity)h.getBlockEntity(b),third=(ScreenBlockEntity)h.getBlockEntity(c);
  h.assertTrue(first.getGridWidth()==1&&first.getGridHeight()==2&&third.getGridWidth()==1&&third.getGridHeight()==2&&second.getGridWidth()==1&&second.getGridHeight()==1,"L cluster no longer splits into rectangles");h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportShapeRotation(GameTestHelper h) {
  var shapes=ChassisShapes.horizontal(net.minecraft.world.phys.shapes.Shapes.box(0,0,0,1,1,0.25));
  h.assertTrue(shapes.get(Direction.NORTH).bounds().maxZ==0.25&&shapes.get(Direction.EAST).bounds().minX==0.75&&shapes.get(Direction.SOUTH).bounds().minZ==0.75&&shapes.get(Direction.WEST).bounds().maxX==0.25,"1.21.1 horizontal shape rotation mismatch");h.succeed();
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportRebuildIsDeferred(GameTestHelper h) {
  final boolean[] returned={false},completed={false};
  // FIFO fence: an inline execute would run before the call site returns.
  var server=h.getLevel().getServer();
  server.tell(new net.minecraft.server.TickTask(server.getTickCount(),()->{
   h.assertTrue(returned[0],"Queued task ran inside its call site");completed[0]=true;
  }));
  var pos=new BlockPos(1,2,1);h.setBlock(pos,ModBlocks.SCREEN);
  ScreenMultiblocks.scheduleRebuild(h.getLevel(),h.absolutePos(pos));returned[0]=true;
  h.runAfterDelay(3,()->{
   h.assertTrue(completed[0],"Server never drained queued tasks");
   ScreenBlockEntity be=(ScreenBlockEntity)h.getBlockEntity(pos);
   h.assertTrue(be.getGridWidth()==1&&be.getGridHeight()==1,"Deferred rebuild lost the screen");h.succeed();
  });
 }
 @GameTest(template="empty",timeoutTicks=200)
 public static void backportScreenNbt(GameTestHelper h) {
  var pos=new BlockPos(1,2,1);h.setBlock(pos,ModBlocks.SCREEN); ScreenBlockEntity be=(ScreenBlockEntity)h.getBlockEntity(pos);UUID id=UUID.randomUUID();var tag=new CompoundTag();tag.putUUID("ConsoleId",id);tag.putInt("GridX",2);tag.putInt("GridY",1);tag.putInt("GridW",3);tag.putInt("GridH",2);
  be.load(tag);var copy=new ScreenBlockEntity(be.getBlockPos(),be.getBlockState());copy.load(be.getUpdateTag());
  h.assertTrue(id.equals(copy.getConsoleId())&&copy.getXIndex()==2&&copy.getYIndex()==1&&copy.getGridWidth()==3&&copy.getGridHeight()==2,"Screen NBT changed during backport");h.succeed();
 }
}
