package it.solphivia.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReadingContinuationTest {
 @Test public void stopMentionElsewhereDoesNotSuppressTheUserThroughoutTheAnswer(){
  String answer="Puoi dire stop. "+"Questa parte della storia parla soltanto del mare. ".repeat(8);
  long token=ReadingContinuation.begin(answer);
  assertEquals(answer,ReadingContinuation.echoContext(answer));
  ReadingContinuation.progress(token,150,155);
  String audible=ReadingContinuation.echoContext(answer);
  assertFalse(audible.contains("stop"));
  assertTrue(new SpeechInterruptPolicy().accept("stop",true,1000,audible,true));
  ReadingContinuation.progress(token,10,14);
  assertFalse(new SpeechInterruptPolicy().accept("stop",true,1000,ReadingContinuation.echoContext(answer),true));
  ReadingContinuation.clear();
 }
 @Test public void pauseResumesAtReportedWord(){ReadingContinuation.clear();long token=ReadingContinuation.begin("Prima frase. Seconda frase.");ReadingContinuation.progress(token,13);ReadingContinuation.pause();assertEquals("Seconda frase.",ReadingContinuation.take());assertFalse(ReadingContinuation.available());}
 @Test public void lateCallbacksCannotOverwritePausedPosition(){ReadingContinuation.clear();long token=ReadingContinuation.begin("Uno due tre");ReadingContinuation.progress(token,4);ReadingContinuation.pause();ReadingContinuation.progress(token,8);ReadingContinuation.complete(token);assertEquals("due tre",ReadingContinuation.take());}
 @Test public void newAnswerReplacesPreviousReading(){ReadingContinuation.clear();long stale=ReadingContinuation.begin("Vecchia");ReadingContinuation.pause();long current=ReadingContinuation.begin("Nuova risposta");ReadingContinuation.progress(stale,6);ReadingContinuation.complete(stale);ReadingContinuation.pause();assertEquals("Nuova risposta",ReadingContinuation.take());ReadingContinuation.complete(current);assertFalse(ReadingContinuation.available());}
 @Test public void completedAndClosedReadingsCannotResume(){ReadingContinuation.clear();long token=ReadingContinuation.begin("Finita");ReadingContinuation.complete(token);ReadingContinuation.pause();assertFalse(ReadingContinuation.available());ReadingContinuation.begin("In corso");ReadingContinuation.pause();ReadingContinuation.clear();assertEquals("",ReadingContinuation.take());}
 @Test public void failedTtsKeepsTheLineForRetry(){ReadingContinuation.clear();long token=ReadingContinuation.begin("Prima frase. Seconda frase.");ReadingContinuation.pause(token);assertEquals("Prima frase. Seconda frase.",ReadingContinuation.take());ReadingContinuation.clear();}
 @Test public void staleErrorCannotPauseNewerReading(){ReadingContinuation.clear();long stale=ReadingContinuation.begin("Vecchia");long current=ReadingContinuation.begin("Nuova risposta");ReadingContinuation.pause(stale);assertEquals("",ReadingContinuation.take());ReadingContinuation.pause();assertEquals("Nuova risposta",ReadingContinuation.take());ReadingContinuation.clear();}
}
