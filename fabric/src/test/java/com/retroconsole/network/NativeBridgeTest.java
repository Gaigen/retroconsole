package com.retroconsole.network;
import com.retroconsole.bridge.LibretroCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="retroconsole.testCore",matches=".+")
class NativeBridgeTest {
 @Test void softwareVideoAudioInputAndSaveState() throws Exception {
  Path temp=Files.createTempDirectory("retroconsole-native-test");
  Path rom=temp.resolve("fixture.bin");Files.write(rom,new byte[]{0});
  try(LibretroCore core=LibretroCore.load(Path.of(System.getProperty("retroconsole.testCore")),temp.toString(),temp.toString())) {
   assertTrue(core.loadGame(rom));
   core.setButton(0,true);core.runFrame();assertEquals(2,core.getWidth());assertEquals(2,core.getHeight());
   int[] frame=new int[4];assertTrue(core.pollFrame(frame));assertTrue(Arrays.stream(frame).anyMatch(p->p!=0));
   short[] audio=new short[64];assertTrue(core.readAudio(audio)>0);assertTrue(audio[0]!=0);
   assertEquals(1,core.getSaveRam()[0]);
   byte[] state=core.serialize();assertNotNull(state);core.runFrame();assertFalse(Arrays.equals(state,core.serialize()));assertTrue(core.unserialize(state));assertArrayEquals(state,core.serialize());
   byte[] ram=core.getSaveRam();ram[1]=42;assertTrue(core.setSaveRam(ram));assertEquals(42,core.getSaveRam()[1]);
  }
 }
}
