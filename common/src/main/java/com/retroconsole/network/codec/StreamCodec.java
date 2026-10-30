package com.retroconsole.network.codec;
import java.util.function.Function;
/** Loader-neutral wire codec for the 1.20.1 networking APIs. */
public interface StreamCodec<B,T> {
 T decode(B buffer);
 void encode(B buffer,T value);
 default <R> StreamCodec<B,R> map(Function<T,R> decode, Function<R,T> encode) {
  StreamCodec<B,T> self=this;
  return new StreamCodec<>() { public R decode(B b){return decode.apply(self.decode(b));} public void encode(B b,R v){self.encode(b,encode.apply(v));} };
 }
 @FunctionalInterface interface F1<A0,R> { R apply(A0 a0); }
 static <B,T,A0> StreamCodec<B,T> composite(StreamCodec<? super B,A0> c0, Function<T,A0> g0, F1<A0,T> factory) {
 return new StreamCodec<>() { public T decode(B b){return factory.apply(c0.decode(b));} public void encode(B b,T v){c0.encode(b,g0.apply(v));} };
 }
 @FunctionalInterface interface F2<A0,A1,R> { R apply(A0 a0,A1 a1); }
 static <B,T,A0,A1> StreamCodec<B,T> composite(StreamCodec<? super B,A0> c0, Function<T,A0> g0,StreamCodec<? super B,A1> c1, Function<T,A1> g1, F2<A0,A1,T> factory) {
 return new StreamCodec<>() { public T decode(B b){return factory.apply(c0.decode(b),c1.decode(b));} public void encode(B b,T v){c0.encode(b,g0.apply(v));c1.encode(b,g1.apply(v));} };
 }
 @FunctionalInterface interface F3<A0,A1,A2,R> { R apply(A0 a0,A1 a1,A2 a2); }
 static <B,T,A0,A1,A2> StreamCodec<B,T> composite(StreamCodec<? super B,A0> c0, Function<T,A0> g0,StreamCodec<? super B,A1> c1, Function<T,A1> g1,StreamCodec<? super B,A2> c2, Function<T,A2> g2, F3<A0,A1,A2,T> factory) {
 return new StreamCodec<>() { public T decode(B b){return factory.apply(c0.decode(b),c1.decode(b),c2.decode(b));} public void encode(B b,T v){c0.encode(b,g0.apply(v));c1.encode(b,g1.apply(v));c2.encode(b,g2.apply(v));} };
 }
 @FunctionalInterface interface F4<A0,A1,A2,A3,R> { R apply(A0 a0,A1 a1,A2 a2,A3 a3); }
 static <B,T,A0,A1,A2,A3> StreamCodec<B,T> composite(StreamCodec<? super B,A0> c0, Function<T,A0> g0,StreamCodec<? super B,A1> c1, Function<T,A1> g1,StreamCodec<? super B,A2> c2, Function<T,A2> g2,StreamCodec<? super B,A3> c3, Function<T,A3> g3, F4<A0,A1,A2,A3,T> factory) {
 return new StreamCodec<>() { public T decode(B b){return factory.apply(c0.decode(b),c1.decode(b),c2.decode(b),c3.decode(b));} public void encode(B b,T v){c0.encode(b,g0.apply(v));c1.encode(b,g1.apply(v));c2.encode(b,g2.apply(v));c3.encode(b,g3.apply(v));} };
 }
 @FunctionalInterface interface F5<A0,A1,A2,A3,A4,R> { R apply(A0 a0,A1 a1,A2 a2,A3 a3,A4 a4); }
 static <B,T,A0,A1,A2,A3,A4> StreamCodec<B,T> composite(StreamCodec<? super B,A0> c0, Function<T,A0> g0,StreamCodec<? super B,A1> c1, Function<T,A1> g1,StreamCodec<? super B,A2> c2, Function<T,A2> g2,StreamCodec<? super B,A3> c3, Function<T,A3> g3,StreamCodec<? super B,A4> c4, Function<T,A4> g4, F5<A0,A1,A2,A3,A4,T> factory) {
 return new StreamCodec<>() { public T decode(B b){return factory.apply(c0.decode(b),c1.decode(b),c2.decode(b),c3.decode(b),c4.decode(b));} public void encode(B b,T v){c0.encode(b,g0.apply(v));c1.encode(b,g1.apply(v));c2.encode(b,g2.apply(v));c3.encode(b,g3.apply(v));c4.encode(b,g4.apply(v));} };
 }
 @FunctionalInterface interface F6<A0,A1,A2,A3,A4,A5,R> { R apply(A0 a0,A1 a1,A2 a2,A3 a3,A4 a4,A5 a5); }
 static <B,T,A0,A1,A2,A3,A4,A5> StreamCodec<B,T> composite(StreamCodec<? super B,A0> c0, Function<T,A0> g0,StreamCodec<? super B,A1> c1, Function<T,A1> g1,StreamCodec<? super B,A2> c2, Function<T,A2> g2,StreamCodec<? super B,A3> c3, Function<T,A3> g3,StreamCodec<? super B,A4> c4, Function<T,A4> g4,StreamCodec<? super B,A5> c5, Function<T,A5> g5, F6<A0,A1,A2,A3,A4,A5,T> factory) {
 return new StreamCodec<>() { public T decode(B b){return factory.apply(c0.decode(b),c1.decode(b),c2.decode(b),c3.decode(b),c4.decode(b),c5.decode(b));} public void encode(B b,T v){c0.encode(b,g0.apply(v));c1.encode(b,g1.apply(v));c2.encode(b,g2.apply(v));c3.encode(b,g3.apply(v));c4.encode(b,g4.apply(v));c5.encode(b,g5.apply(v));} };
 }
}
