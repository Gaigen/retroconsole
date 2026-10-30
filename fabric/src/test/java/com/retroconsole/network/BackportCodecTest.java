package com.retroconsole.network;

import com.retroconsole.network.codec.*;
import com.retroconsole.network.payload.*;
import io.netty.buffer.*;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackportCodecTest {
 @Test void allEighteenPayloadsRoundTrip() throws Exception {
  Set<String> ids=new HashSet<>();
  for(Field field:RetroPayloads.class.getFields()) {
   if(field.getType()!=NetPayload.class)continue;
   NetPayload<?> definition=(NetPayload<?>)field.get(null);
   assertTrue(ids.add(definition.id()));
   Class<?> type=(Class<?>)((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
   roundTrip(definition,sample(type));
  }
  assertEquals(18,ids.size());
 }
 @SuppressWarnings({"rawtypes","unchecked"})
 private void roundTrip(NetPayload definition,Object original) {
  ByteBuf buf=Unpooled.buffer(),encodedAgain=Unpooled.buffer();
  try {
   definition.codec().encode(buf,original);
   byte[] wire=new byte[buf.readableBytes()];buf.getBytes(0,wire);
   Object decoded=definition.codec().decode(buf);
   assertEquals(0,buf.readableBytes(),definition.id());
   definition.codec().encode(encodedAgain,decoded);
   byte[] actual=new byte[encodedAgain.readableBytes()];encodedAgain.readBytes(actual);
   assertArrayEquals(wire,actual,definition.id());
  } finally {buf.release();encodedAgain.release();}
 }
 private Object sample(Type type) throws Exception {
  if(type instanceof ParameterizedType p && p.getRawType()==List.class)return List.of(sample(p.getActualTypeArguments()[0]));
  Class<?> c=(Class<?>)type;
  if(c==String.class)return "тест/rom.nes";
  if(c==int.class)return 2;
  if(c==long.class)return 123456789012L;
  if(c==short.class)return (short)-123;
  if(c==boolean.class)return true;
  if(c==byte[].class)return new byte[]{0,1,-1,127};
  if(c==UUID.class)return new UUID(1,2);
  if(c==BlockPos.class)return new BlockPos(-123,64,456);
  if(c.isRecord()) {
   var fields=c.getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] values=new Object[fields.length];
   for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=sample(fields[i].getGenericType());}
   var ctor=c.getDeclaredConstructor(types);ctor.setAccessible(true);return ctor.newInstance(values);
  }
  throw new AssertionError(c);
 }
 @Test void boundedCodecsRejectInvalidLengths(){
  FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
  try {
   b.writeVarInt(4097);
   assertThrows(DecoderException.class,()->ByteBufCodecs.collection(ArrayList::new,ByteBufCodecs.VAR_INT).decode(b));
   b.clear();b.writeVarInt(-1);
   assertThrows(DecoderException.class,()->ByteBufCodecs.byteArray(32).decode(b));
   b.clear();b.writeVarInt(33);
   assertThrows(DecoderException.class,()->ByteBufCodecs.byteArray(32).decode(b));
  } finally {b.release();}
 }
 @Test void compressedFrameRetainsPixels(){
  int[] pixels={0xff112233,0xffaabbcc,0xff000000,0xffffffff};
  var packet=RetroFramePacket.create(new UUID(1,2),pixels,2,2);
  assertArrayEquals(new int[]{0xff332211,0xffccbbaa,0xff000000,0xffffffff},packet.decompressFrameAbgr());
 }
}
