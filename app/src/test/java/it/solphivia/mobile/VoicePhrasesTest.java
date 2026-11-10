package it.solphivia.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoicePhrasesTest {
 @Test public void canonicalGreetingIsRecognized(){assertEquals("",VoicePhrases.wakeRequest("Hola Solphivia"));assertEquals("",VoicePhrases.wakeRequest("ola Solfivia"));assertEquals("",VoicePhrases.wakeRequest("Ciao Solphivia"));}
 @Test public void italianAsrRenderingsOfHolaAreRecognized(){
  assertEquals("",VoicePhrases.wakeRequest("Ho la Solphivia"));
  assertEquals("",VoicePhrases.wakeRequest("o la solphivia"));
  assertEquals("",VoicePhrases.wakeRequest("o la sul fibbia"));
  assertEquals("",VoicePhrases.wakeRequest("hola solfivia"));
  assertEquals("",VoicePhrases.wakeRequest("o la sofia"));
  assertEquals("",VoicePhrases.wakeRequest("ciao silvia"));
 }
 @Test public void trailingWordsAreKept(){assertEquals("come stai",VoicePhrases.wakeRequest("Ho la Solphivia come stai"));}
 @Test public void ordinarySentencesAreNotRewritten(){
  assertNull(VoicePhrases.wakeRequest("ho la febbre"));
  assertNull(VoicePhrases.wakeRequest("mi chiamo Anna"));
  assertNull(VoicePhrases.wakeRequest("che tempo fa oggi"));
 }
}
