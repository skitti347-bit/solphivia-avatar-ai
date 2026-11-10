package it.solphivia.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;

/** Inline screen reader: female neural voice via the free Edge TTS API. */
final class DeviceSpeech {
    private static volatile boolean outputBusy;
    private static volatile DeviceSpeech active;
    private volatile String speakingText="";
    static String spokenText(){return outputBusy&&active!=null?active.speakingText:"";}
    static void interruptAll(){DeviceSpeech current=active;if(current!=null)current.stop();}
    static boolean outputBusy(){return outputBusy;}
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    DeviceSpeech(Context context){this.context=context.getApplicationContext();}
    void stop(){EdgeTts.stop();if(active==this){active=null;outputBusy=false;}speakingText="";}
    void speak(String text){
        android.content.SharedPreferences prefs=context.getSharedPreferences("pet",0);
        if(!prefs.getBoolean("read_aloud",true)){stop();return;}
        speak(text,"",prefs.getBoolean("english_accent",false),
                prefs.getBoolean("clear_italian",true),prefs.getInt("voice_rate_percent",100),null);
    }
    void speak(String text,String engine,boolean requestedEnglish,boolean clear,int ratePercent,java.util.function.Consumer<String> feedback){
        interruptAll();stop();active=this;speakingText=text==null?"":text;
        outputBusy=true;
        String prepared=SpeechText.prepare(text,requestedEnglish,clear).trim();
        if(prepared.isEmpty()){outputBusy=false;return;}
        PetService.noteLastSpoken(prepared);
        if(feedback!=null)feedback.accept("Provo con la voce di Solphivia (Elsa · Edge).");
        String voice=requestedEnglish?EdgeTts.ENGLISH_VOICE:EdgeTts.DEFAULT_VOICE;
        long token=ReadingContinuation.begin(prepared);
        List<String> chunks=SpeechText.chunks(prepared,800);
        readChunks(chunks,0,token,voice,ratePercent,0);
    }

    /** Chains chunk synthesis/playback on the main thread; keeps ReadingContinuation progress. */
    private void readChunks(List<String> chunks,int index,long token,String voice,int ratePercent,int baseOffset){
        if(index>=chunks.size()){outputBusy=false;ReadingContinuation.complete(token);return;}
        if(active!=this){ReadingContinuation.complete(token);return;}
        ReadingContinuation.progress(token,baseOffset,baseOffset+chunks.get(index).length());
        int nextBase=baseOffset+chunks.get(index).length();
        EdgeTts.speak(context,chunks.get(index),voice,ratePercent,new EdgeTts.Listener(){
            @Override public void onDone(){main.post(()->{if(active==DeviceSpeech.this)readChunks(chunks,index+1,token,voice,ratePercent,nextBase);});}
            @Override public void onError(String message){
                main.post(()->{if(active==DeviceSpeech.this){outputBusy=false;ReadingContinuation.pause(token);}});
            }
        });
    }
}
