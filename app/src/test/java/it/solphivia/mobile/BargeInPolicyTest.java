package it.solphivia.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class BargeInPolicyTest {
 @Test public void sustainedSpeechWithEchoCancellationTriggersInThreeFrames(){
  BargeInPolicy policy=new BargeInPolicy(true);
  for(int i=0;i<10;i++)assertFalse(policy.frame(100)); // silenzio: calibra il pavimento
  assertFalse(policy.frame(1500));
  assertFalse(policy.frame(1500));
  assertTrue(policy.frame(1500));
 }
 @Test public void withoutEchoCancellationItDemandsLongerSpeech(){
  BargeInPolicy policy=new BargeInPolicy(false);
  for(int i=0;i<10;i++)policy.frame(100);
  for(int i=0;i<4;i++)assertFalse(policy.frame(1500));
  assertTrue(policy.frame(1500));
 }
 @Test public void quietRoomNoiseNeverTriggers(){
  BargeInPolicy policy=new BargeInPolicy(true);
  for(int i=0;i<90;i++)assertFalse(policy.frame(100));
 }
 @Test public void speechBelowAbsoluteFloorNeverTriggers(){
  BargeInPolicy policy=new BargeInPolicy(true);
  for(int i=0;i<60;i++)assertFalse(policy.frame(800));
 }
 @Test public void aSingleQuietFrameDoesNotForgetOngoingSpeech(){
  BargeInPolicy policy=new BargeInPolicy(true);
  for(int i=0;i<10;i++)policy.frame(100);
  assertFalse(policy.frame(1500));
  assertFalse(policy.frame(1500));
  assertFalse(policy.frame(150));
  assertFalse(policy.frame(1500));
  assertTrue(policy.frame(1500));
 }
 @Test public void sustainedLoudSoundRaisesTheBarThenQuietReArms(){
  BargeInPolicy policy=new BargeInPolicy(true);
  for(int i=0;i<60;i++)policy.frame(1500);
  assertFalse(policy.frame(1500)); // suono continuo forte: la soglia sale sopra l'eco
  assertFalse(policy.frame(150)); // pausa: il minimo della finestra torna basso
  assertFalse(policy.frame(1500));
  assertFalse(policy.frame(1500));
  assertTrue(policy.frame(1500)); // riarmato
 }
 @Test public void resetStartsOver(){
  BargeInPolicy policy=new BargeInPolicy(true);
  for(int i=0;i<10;i++)policy.frame(100);
  policy.frame(1500);policy.frame(1500);policy.frame(1500);
  policy.reset();
  assertFalse(policy.frame(1500));
 }
}
