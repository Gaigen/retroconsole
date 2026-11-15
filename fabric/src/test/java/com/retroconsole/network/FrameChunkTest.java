package com.retroconsole.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameChunkTest {
 @Test void largeFrameRoundTripsWithoutResizing() {
  int width=800,height=960; int[] pixels=new int[width*height]; Random r=new Random(17);
  for(int i=0;i<pixels.length;i++) pixels[i]=0xff000000|r.nextInt();
  var frame=RetroFramePacket.create(UUID.randomUUID(),pixels,width,height);
  assertTrue(frame.compressedFrame().length>1048576);
  var chunks=RetroFrameChunk.split(frame,42); assertTrue(chunks.size()>1);
  FrameChunkAssembler assembler=new FrameChunkAssembler(); RetroFramePacket result=null;
  for(var c:chunks) {
   var wire=new FriendlyByteBuf(Unpooled.buffer());
   try {
    RetroFrameChunk.STREAM_CODEC.encode(wire,c); assertTrue(wire.readableBytes()<1048576-64);
    RetroFrameChunk decoded=RetroFrameChunk.STREAM_CODEC.decode(wire); assertEquals(0,wire.readableBytes());
    result=assembler.accept(decoded);
   } finally {wire.release();}
  }
  assertNotNull(result); assertEquals(width,result.width()); assertEquals(height,result.height());
  assertArrayEquals(frame.compressedFrame(),result.compressedFrame());
  assertArrayEquals(frame.decompressFrameAbgr(),result.decompressFrameAbgr());
 }
 @Test void missingOrWrongChunksNeverProduceFrame() {
  byte[] bytes=new byte[RetroFrameChunk.CHUNK_BYTES+17]; UUID id=UUID.randomUUID();
  var chunks=RetroFrameChunk.split(new RetroFramePacket(id,800,960,bytes),1);
  var a=new FrameChunkAssembler(); assertNull(a.accept(chunks.get(1)));
  assertNull(a.accept(chunks.get(0))); a.remove(id); assertNull(a.accept(chunks.get(1)));
  assertNull(a.accept(chunks.get(0)));
  var wrong=new RetroFrameChunk(id,2,800,960,bytes.length,1,chunks.get(1).data());
  assertNull(a.accept(wrong)); assertNull(a.accept(chunks.get(1)));
  assertNull(a.accept(chunks.get(0))); assertNotNull(a.accept(chunks.get(1)));
 }
 @Test void malformedAndOversizedChunksAreRejected() {
  UUID id=UUID.randomUUID();
  assertThrows(DecoderException.class,()->new RetroFrameChunk(id,1,800,960,100,0,new byte[99]));
  assertThrows(DecoderException.class,()->new RetroFrameChunk(id,1,4097,960,1,0,new byte[1]));
  assertThrows(DecoderException.class,()->new RetroFrameChunk(id,1,1,1,1000000,0,new byte[RetroFrameChunk.CHUNK_BYTES]));
 }
}
