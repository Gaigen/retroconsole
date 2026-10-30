package com.retroconsole.network.codec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.function.*;
/** Bounded primitive codecs; identical field ordering on Forge and Fabric. */
public final class ByteBufCodecs {
 private ByteBufCodecs(){}
 private static <T> StreamCodec<ByteBuf,T> codec(Function<FriendlyByteBuf,T> reader,BiConsumer<FriendlyByteBuf,T> writer){
  return new StreamCodec<>() { public T decode(ByteBuf b){return reader.apply(new FriendlyByteBuf(b));} public void encode(ByteBuf b,T v){writer.accept(new FriendlyByteBuf(b),v);} };
 }
 public static final StreamCodec<ByteBuf,Boolean> BOOL=codec(FriendlyByteBuf::readBoolean,(b,v)->b.writeBoolean(v));
 public static final StreamCodec<ByteBuf,Short> SHORT=codec(FriendlyByteBuf::readShort,(b,v)->b.writeShort(v));
 public static final StreamCodec<ByteBuf,Integer> VAR_INT=codec(FriendlyByteBuf::readVarInt,(b,v)->b.writeVarInt(v));
 public static final StreamCodec<ByteBuf,Long> VAR_LONG=codec(FriendlyByteBuf::readVarLong,(b,v)->b.writeVarLong(v));
 public static final StreamCodec<ByteBuf,BlockPos> BLOCK_POS=codec(FriendlyByteBuf::readBlockPos,(b,v)->b.writeBlockPos(v));
 public static final StreamCodec<ByteBuf,byte[]> BYTE_ARRAY=byteArray(1048576);
 public static StreamCodec<ByteBuf,String> stringUtf8(int max){return codec(b->b.readUtf(max),(b,v)->b.writeUtf(v,max));}
 public static StreamCodec<ByteBuf,byte[]> byteArray(int max){return codec(b->{int n=b.readVarInt();if(n<0||n>max||n>b.readableBytes())throw new DecoderException("Invalid byte array length: "+n);byte[] bytes=new byte[n];b.readBytes(bytes);return bytes;},(b,v)->{if(v.length>max)throw new EncoderException("Byte array exceeds "+max);b.writeByteArray(v);});}
 public static <T,C extends Collection<T>> StreamCodec<ByteBuf,C> collection(IntFunction<C> factory,StreamCodec<ByteBuf,T> element){
  return codec(b->{int n=b.readVarInt();if(n<0||n>4096)throw new DecoderException("Invalid collection size: "+n); C result=factory.apply(n);for(int i=0;i<n;i++)result.add(element.decode(b));return result;},
   (b,v)->{if(v.size()>4096)throw new EncoderException("Collection too large");b.writeVarInt(v.size());for(T item:v)element.encode(b,item);});
 }
}
