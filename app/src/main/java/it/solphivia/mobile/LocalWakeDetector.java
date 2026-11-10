package it.solphivia.mobile;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.LibVosk;
import org.json.JSONObject;
import java.io.*;

/** Continuous Italian recognition in memory; waiting audio never leaves the device. */
final class LocalWakeDetector implements AutoCloseable {
    private static final java.util.concurrent.ExecutorService CAPTURE=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"solphivia-local-wake"));
    private static Model sessionModel;
    static void releaseSessionModel(){CAPTURE.execute(()->{if(sessionModel!=null){sessionModel.close();sessionModel=null;}});}
    interface Listener {
        void wake();
        void failed(String reason);
        void samples(long count);
        default void interrupted(String text) {}
        default void bargedIn(String heard) {}
        default void resumed(String text) {}
        default void partialCommand(String text) {}
        default void command(String text) {}
        default void controlResult(boolean candidate, boolean verified) {}
        default void echo(boolean available, boolean enabled) {}
    }
    private enum Mode { WAKE, INTERRUPT, DIALOGUE }
    private final Context context;
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean suspended;
    private volatile Mode requestedMode = Mode.WAKE;
    private volatile String spoken="";
    private final SpeechInterruptPolicy interruptPolicy=new SpeechInterruptPolicy();
    private volatile AudioRecord recorder;
    LocalWakeDetector(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    static synchronized Model model(Context context)throws IOException{
        LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
        File root=new File(context.getNoBackupFilesDir(),"wake-it-0.22");
        File ready=new File(root,"complete-v1");
        if(!ready.exists()){
            copyAssets(context,"wake-it",root);
            try(FileOutputStream out=new FileOutputStream(ready)){out.write(1);}
        }
        return new Model(root.getAbsolutePath());
    }
    private static void copyAssets(Context context,String path,File target)throws IOException{
        String[] names=context.getAssets().list(path);
        if(names!=null&&names.length>0){
            if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Model directory unavailable");
            for(String name:names)copyAssets(context,path+"/"+name,new File(target,name));
        }else try(InputStream in=context.getAssets().open(path);OutputStream out=new FileOutputStream(target)){
            byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }
    static String text(String json){try{return new JSONObject(json).optString("text","");}catch(Exception ignored){return "";}}
    static Recognizer recognizer(Model model,float rate,String learned)throws IOException{
        org.json.JSONArray phrases=new org.json.JSONArray();
        // The Italian dictionary spells the new name with existing, similar sounds.
        // Keep confusable names and an unknown-speech path so unrelated speech can be rejected.
        for(String phrase:new String[]{"o la sol fibbia","o la sul fibbia","o la sua figlia",
                "o la sofia","ciao silvia","ho scattato un selfie","[unk]"})phrases.put(phrase);
        if(learned!=null)for(String phrase:learned.split("\n"))if(!phrase.isEmpty())phrases.put(phrase);
        if(ReadingContinuation.available())for(String phrase:new String[]{"prosegui","riprendi","continua","dimmi","ti ascolto","dai","vai"}){phrases.put(phrase);phrases.put(phrase+" sol fibbia");phrases.put(phrase+" sul fibbia");}
        return new Recognizer(model,rate,phrases.toString());
    }
    static String partial(String json){try{return new JSONObject(json).optString("partial","");}catch(Exception ignored){return "";}}
    static boolean matches(String text,String learned){
        String value=VoicePhrases.normalized(text);
        if(value.matches("(?:hola|ola|ciao|ehi|hey) (?:solphivia|solfivia)"))return true;
        // Whole-utterance Italian ASR renderings observed on synthetic greetings.
        if(value.equals("o la sul fibbia")||value.equals("o la sol fibbia"))return true;
        if(learned!=null)for(String example:learned.split("\n"))if(!example.isEmpty()&&value.equals(example))return true;
        return false;
    }
    void start(){running=true;CAPTURE.execute(this::capture);}
    void suspend(boolean value){suspended=value;}
    void interruptDuring(String text){spoken=text==null?"":text;requestedMode=Mode.INTERRUPT;suspended=false;}
    void waitForWake(){requestedMode=Mode.WAKE;suspended=false;}
    void waitForCommand(){requestedMode=Mode.DIALOGUE;suspended=false;}
    private Recognizer decoder(Model model, Mode mode, String learned) throws IOException {
        if (mode == Mode.DIALOGUE) return new Recognizer(model, 16000);
        return mode == Mode.INTERRUPT ? interruptRecognizer(model, 16000) : recognizer(model, 16000, learned);
    }
    static Recognizer interruptRecognizer(Model model,float rate)throws IOException{
        org.json.JSONArray phrases=new org.json.JSONArray();
        for(String verb:new String[]{"basta","fermati","stop","alt"}){phrases.put(verb);phrases.put(verb+" sol fibbia");phrases.put(verb+" sul fibbia");}
        for (String first : new String[]{"basta", "fermati", "stop", "alt"}) {
            for (String second : new String[]{"basta", "fermati", "stop", "alt"}) phrases.put(first + " " + second);
        }
        for(String phrase:new String[]{"basta sofia","fermati silvia","basta cosi","[unk]"})phrases.put(phrase);
        return new Recognizer(model,rate,phrases.toString());
    }
    private static boolean canonicalNamed(String text){return SpeechInterruptPolicy.canonical(text).endsWith(" solphivia");}
    private void capture(){
        Model model=null;Recognizer decoder=null,verifier=null;AudioRecord input=null;
        boolean detected=false,interrupted=false,resumed=false,command=false;String failure=null,controlText="";
        android.media.audiofx.AcousticEchoCanceler echo=null;
        BargeInPolicy barge=new BargeInPolicy(false);boolean aecEnabled=false;
        boolean bargeAllowed=context.getSharedPreferences("pet",0).getBoolean("barge_in",true);
        long bargeGraceUntil=0;boolean barged=false;int bargePeak=0,bargeFrames=0;
        try{
            if(!running)return;
            String learned=context.getSharedPreferences("pet",0).getString("wake_italian_phrases","");
            if(sessionModel==null)sessionModel=model(context);model=sessionModel;
            Mode mode=requestedMode;decoder=decoder(model,mode,learned);
            if(mode==Mode.INTERRUPT || mode==Mode.WAKE && ReadingContinuation.available())verifier=new Recognizer(model,16000);String verifiedFinal="";
            if(context.checkSelfPermission("android.permission.RECORD_AUDIO")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microfono non autorizzato");
            int size=Math.max(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            input=new AudioRecord(context.getSharedPreferences("pet",0).getBoolean("voice_interrupt",true)?MediaRecorder.AudioSource.VOICE_COMMUNICATION:MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
            recorder=input;if(!running)return;
            if(input.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microfono locale non disponibile");
            boolean available=android.media.audiofx.AcousticEchoCanceler.isAvailable(),enabled=false;
            if(available)try{echo=android.media.audiofx.AcousticEchoCanceler.create(input.getAudioSessionId());if(echo!=null)enabled=echo.setEnabled(true)==android.media.audiofx.AudioEffect.SUCCESS&&echo.getEnabled();}catch(RuntimeException ignored){}
            listener.echo(available,enabled);
            aecEnabled=enabled;barge=new BargeInPolicy(enabled);
            input.startRecording();short[] pcm=new short[1600];long total=0;int utterance=0;boolean wasSuspended=false;
            while(running){
                int n=input.read(pcm,0,pcm.length);if(!running)break;if(n<0)throw new IOException("Microphone read failed");if(n==0)continue;
                total+=n;utterance+=n;
                if(total%32000<n)listener.samples(total);
                Mode nextMode=requestedMode;
                if(nextMode!=mode){Recognizer previous=decoder;decoder=null;previous.close();if(verifier!=null){verifier.close();verifier=null;}verifiedFinal="";mode=nextMode;if(mode==Mode.INTERRUPT || mode==Mode.WAKE && ReadingContinuation.available())verifier=new Recognizer(model,16000);decoder=decoder(model,mode,learned);interruptPolicy.reset();utterance=0;barge.reset();bargeGraceUntil=SystemClock.elapsedRealtime()+800;}
                boolean paused=suspended;
                if(paused!=wasSuspended){decoder.reset();if(verifier!=null)verifier.reset();verifiedFinal="";utterance=0;barge.reset();wasSuspended=paused;}
                if(paused){utterance=0;continue;}
                if(verifier!=null&&verifier.acceptWaveForm(pcm,n))verifiedFinal=(verifiedFinal+" "+text(verifier.getResult())).trim();
                boolean finalResult=decoder.acceptWaveForm(pcm,n);
                if(mode==Mode.DIALOGUE){
                    String phrase=finalResult?text(decoder.getResult()):partial(decoder.getPartialResult());
                    if(!phrase.isEmpty()){
                        if(finalResult){command=true;controlText=phrase;break;}
                        listener.partialCommand(phrase);
                    }
                }else if(mode==Mode.INTERRUPT){
                    if(bargeAllowed&&SystemClock.elapsedRealtime()>=bargeGraceUntil){
                        long sum=0;for(int i=0;i<n;i++){int sample=pcm[i];sum+=(long)sample*sample;}
                        int rms=(int)Math.sqrt(sum/(double)n);
                        if(rms>bargePeak)bargePeak=rms;
                        if(++bargeFrames>=30){final int peak=bargePeak;bargePeak=0;bargeFrames=0;
                            context.getSharedPreferences("voice_health",0).edit().putInt("barge_rms_peak",peak).apply();}
                        if(barge.frame(rms)){
                            barged=true;
                            String heard=(verifiedFinal+" "+(verifier==null?"":partial(verifier.getPartialResult()))).trim();
                            controlText=heard;break;
                        }
                    }
                    String phrase=finalResult?text(decoder.getResult()):partial(decoder.getPartialResult());
                    String verified=verifier==null?"":(verifiedFinal+" "+(finalResult?text(verifier.getFinalResult()):partial(verifier.getPartialResult()))).trim();
                    String source=ReadingContinuation.sourceText(spoken);
                    boolean allowed=canonicalNamed(phrase)||SpeechInterruptPolicy.corroborates(phrase,verified,source);
                    if(finalResult)listener.controlResult(SpeechInterruptPolicy.command(phrase),allowed);
                    if(finalResult&&verifier!=null){verifier.reset();verifiedFinal="";}
                    if(interruptPolicy.acceptRecognition(phrase,verified,finalResult,android.os.SystemClock.elapsedRealtime(),spoken,source)){if(!finalResult)listener.controlResult(true,true);interrupted=true;controlText=phrase;break;}
                }else if(finalResult){String phrase=text(decoder.getResult());
                    String verified=verifier!=null?(verifiedFinal+" "+text(verifier.getFinalResult())).trim():"";
                    if(verifier!=null){verifier.reset();verifiedFinal="";}
                    if(ReadingContinuation.available()&&SpeechInterruptPolicy.resumeCommand(phrase)&&SpeechInterruptPolicy.canonical(phrase).equals(SpeechInterruptPolicy.canonical(verified))){resumed=true;controlText=phrase;break;}
                    if(matches(phrase,learned)){detected=true;break;}
                }
                if(finalResult)utterance=0;
                if(mode!=Mode.DIALOGUE && utterance>320000){decoder.reset();if(verifier!=null)verifier.reset();verifiedFinal="";utterance=0;}
            }
        }catch(Throwable error){if(running)failure=error.getClass().getSimpleName();}
        finally{
            recorder=null;
            if(input!=null){try{input.stop();}catch(RuntimeException ignored){}input.release();}
            if(echo!=null)echo.release();
            if(decoder!=null)decoder.close();
            if(verifier!=null)verifier.close();
        }
        if(running){if(command)listener.command(controlText);else if(resumed)listener.resumed(controlText);else if(barged)listener.bargedIn(controlText);else if(interrupted)listener.interrupted(controlText);else if(detected)listener.wake();else if(failure!=null)listener.failed(failure);}
        running=false;
    }
    public void close(){running=false;AudioRecord input=recorder;if(input!=null)try{input.stop();}catch(RuntimeException ignored){}}
}
