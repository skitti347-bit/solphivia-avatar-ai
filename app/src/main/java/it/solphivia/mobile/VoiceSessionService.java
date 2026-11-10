package it.solphivia.mobile;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.speech.*;
import java.util.ArrayList;

/** Local wake listening; Android speech recognition runs only for a requested command. */
public final class VoiceSessionService extends Service implements RecognitionListener {
    static final String INTERRUPT="it.solphivia.voice.INTERRUPT";
    static final String START="it.solphivia.voice.START", WAKE="it.solphivia.voice.WAKE", PAUSE="it.solphivia.voice.PAUSE", OFF="it.solphivia.voice.OFF";
    private static VoiceSessionService instance;
    private static String statusText="Microfono spento.";
    static String statusText(){return statusText;}
    private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private LocalWakeDetector localWake;
    private final VoiceStatusState voiceStatus = new VoiceStatusState();
    private final VoiceFollowUpWindow followUp = new VoiceFollowUpWindow();
    private long wakeGeneration;
    private int commandStarts;
    private boolean listening,stopped,foregroundReady;
    private boolean localConversation;
    private final VoiceSessionState session=new VoiceSessionState();
    private long nextListen;
    private long nextWakeAttempt;
    private long recognitionStarted;
    private long recognitionGeneration;
    private VoiceTranscriptComposer transcript;
    private void cancelTranscript(){if(transcript!=null){transcript.cancel();transcript=null;}}
    static boolean running(){return instance!=null;}
    static boolean ready(){return instance!=null&&instance.foregroundReady&&!instance.stopped;}
    static boolean backgroundRestricted(android.content.Context context){
        return Build.VERSION.SDK_INT>=28&&context.getSystemService(ActivityManager.class).isBackgroundRestricted();
    }
    @Override public void onCreate(){super.onCreate();instance=this;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&INTERRUPT.equals(intent.getAction())){
            if(foregroundReady){interruptReading();return START_STICKY;}
            PetService.stopReading();stopSelf();return START_NOT_STICKY;
        }
        boolean recovery=intent==null;
        if((recovery&&!getSharedPreferences("pet",0).getBoolean("voice_requested",false)) || (intent!=null&&OFF.equals(intent.getAction()))){stopSelf();return START_NOT_STICKY;}
        if(backgroundRestricted(this)){statusText="Ascolto interrotto: Android limita Solphivia in background. Consenti l’uso in background nelle impostazioni batteria dell’app.";stopSelf();return START_NOT_STICKY;}
        if(checkSelfPermission("android.permission.RECORD_AUDIO")!=android.content.pm.PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
        if(!SpeechRecognizer.isRecognitionAvailable(this)){notifyState("Riconoscimento vocale non disponibile");stopSelf();return START_NOT_STICKY;}
        session.paused=recovery||PAUSE.equals(intent.getAction()) || WAKE.equals(intent.getAction());
        localConversation=false;
        followUp.rest();
        recognitionGeneration++;cancelTranscript();
        getSharedPreferences("pet",0).edit().putBoolean("voice_requested",true).apply();
        if(listening){listening=false;if(recognizer!=null)recognizer.cancel();PetService.voiceListening(false);}
        closeWake();
        try{notifyState(session.paused?"Richiamo attivo: Hola Solphivia":"Sessione vocale attiva");}
        catch(RuntimeException error){statusText="Ascolto interrotto: riapri Solphivia per autorizzare il microfono.";getSharedPreferences("pet",0).edit().putBoolean("voice_requested",false).commit();stopSelf();return START_NOT_STICKY;}
        main.removeCallbacks(tick);main.post(tick);
        MainActivity.voiceServiceReady();
        if(recovery){
            android.content.SharedPreferences health=getSharedPreferences("voice_health",0);
            health.edit().putInt("system_recoveries",health.getInt("system_recoveries",0)+1).apply();
            if(!getSharedPreferences("pet",0).getBoolean("overlay_off",false))startForegroundService(new Intent(this,PetService.class));
        }
        return START_STICKY;
    }
    static void interruptReading(){
        PetService.stopReading();
        VoiceSessionService service=instance;
        if(service!=null&&!service.stopped){
            service.closeWake();service.followUp.rest();service.session.paused=false;
            service.localConversation=false;
            service.nextListen=SystemClock.elapsedRealtime()+1200;
            service.voiceStatus.idle();service.notifyState("Lettura fermata · attendi il segnale, poi parla");
        }
    }
    private static void showControlTranscript(String text){android.widget.EditText field=MainActivity.voiceComposer();if(field==null)field=PetService.voiceComposer();if(field!=null&&(field.length()==0||SentVoiceTranscript.owns(field)))SentVoiceTranscript.hold(field,text);}
    static boolean handleReadingCommand(String text){
        if(SpeechInterruptPolicy.command(text)){interruptReading();return true;}
        if(SpeechInterruptPolicy.resumeCommand(text)&&ReadingContinuation.available()){
            VoiceSessionService service=instance;if(service!=null)service.closeWake();
            return PetService.resumeReading();
        }
        return false;
    }
    private void closeWake(){wakeGeneration++;if(localWake!=null){localWake.close();localWake=null;}}
    private void deliver(String text){localConversation=false;followUp.replyStarted();if(!MainActivity.acceptVoiceMessage(text))startForegroundService(new Intent(this,PetService.class).putExtra(PetService.EXTRA_SEND_MESSAGE,text).putExtra("voice_message",true));}
    private void restForWake(){
        cancelTranscript();
        listening=false;localConversation=false;recognitionGeneration++;followUp.rest();session.paused=true;voiceStatus.idle();
        if(recognizer!=null)recognizer.cancel();
        PetService.voiceListening(false);nextListen=SystemClock.elapsedRealtime()+250;
        notifyState("A riposo · di’ Hola Solphivia per riprendere");
    }
    private void listenLocally() {
        listening=false;
        recognitionGeneration++;
        if(recognizer!=null)recognizer.cancel();
        cancelTranscript();
        closeWake();
        followUp.rest();
        session.paused=false;
        localConversation=true;
        nextListen=SystemClock.elapsedRealtime()+300;
        notifyState("Dialogo in ascolto locale · parla oppure di’ Stop dialogo");
    }
    private void showLocalCommand(String text) {
        if(transcript==null){
            android.widget.EditText field=MainActivity.voiceComposer();
            if(field==null)field=PetService.voiceComposer();
            if(field!=null)transcript=new VoiceTranscriptComposer(field);
        }
        if(transcript!=null)transcript.show(text);
    }
    private void startCapture(){
        cancelTranscript();
        android.widget.EditText field=MainActivity.voiceComposer();
        if(field==null)field=PetService.voiceComposer();
        if(field!=null)transcript=new VoiceTranscriptComposer(field);
        long generation=++recognitionGeneration;
        if(recognizer!=null){recognizer.cancel();recognizer.destroy();}
        recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener(){
            private boolean current(){return !stopped && listening && recognitionGeneration==generation;}
            public void onReadyForSpeech(Bundle p){if(current())VoiceSessionService.this.onReadyForSpeech(p);}
            public void onBeginningOfSpeech(){if(current())VoiceSessionService.this.onBeginningOfSpeech();}
            public void onEndOfSpeech(){if(current())VoiceSessionService.this.onEndOfSpeech();}
            public void onError(int e){if(current())VoiceSessionService.this.onError(e);}
            public void onResults(Bundle b){if(current())VoiceSessionService.this.onResults(b);}
            public void onRmsChanged(float r){} public void onBufferReceived(byte[] b){}
            public void onPartialResults(Bundle b){if(current())VoiceSessionService.this.onPartialResults(b);} public void onEvent(int t,Bundle b){}
        });
        recognizer.startListening(VoicePhrases.recognitionIntent());
    }
    private void startWake(){
        if(SystemClock.elapsedRealtime()<nextWakeAttempt)return;
        long generation=++wakeGeneration;
        localWake=new LocalWakeDetector(this,new LocalWakeDetector.Listener(){
            public void wake(){main.post(()->{if(stopped || wakeGeneration!=generation)return;if(!session.paused||PetService.voiceOutputBusy()){closeWake();return;}closeWake();session.paused=false;nextListen=SystemClock.elapsedRealtime()+1000;notifyState("Saluto riconosciuto · ascolto il comando dopo la risposta");deliver("Hola Solphivia");});}
            public void failed(String reason){main.post(()->{
                if(stopped||wakeGeneration!=generation)return;
                closeWake();
                getSharedPreferences("voice_health",0).edit().putString("local_capture_error",reason).apply();
                if("SecurityException".equals(reason)){stopSelf();return;}
                nextWakeAttempt=SystemClock.elapsedRealtime()+1500;
                nextListen=nextWakeAttempt;
                notifyState("Ripristino del microfono locale in corso");
            });}
            public void partialCommand(String text){main.post(()->{
                if(!stopped&&wakeGeneration==generation&&localConversation&&!session.paused&&!PetService.voiceOutputBusy())showLocalCommand(text);
            });}
            public void command(String text){main.post(()->{
                if(stopped||wakeGeneration!=generation||!localConversation||session.paused||PetService.voiceOutputBusy())return;
                closeWake();showLocalCommand(text);localConversation=false;listening=true;
                Bundle result=new Bundle();
                result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(java.util.Collections.singletonList(text)));
                onResults(result);
            });}
            public void controlResult(boolean candidate,boolean verified){
                android.content.SharedPreferences health=getSharedPreferences("voice_health",0);
                health.edit().putInt("interrupt_segments",health.getInt("interrupt_segments",0)+1)
                    .putInt("interrupt_candidates",health.getInt("interrupt_candidates",0)+(candidate?1:0))
                    .putInt("interrupt_verified",health.getInt("interrupt_verified",0)+(verified?1:0)).apply();
            }
            public void resumed(String text){main.post(()->{
                if(stopped||wakeGeneration!=generation)return;
                closeWake();if(!ReadingContinuation.available())return;showControlTranscript(text);session.paused=false;followUp.replyStarted();PetService.resumeReading();
            });}
            public void bargedIn(String heard){main.post(()->{
                if(stopped||wakeGeneration!=generation)return;
                closeWake();
                if(!getSharedPreferences("pet",0).getBoolean("voice_interrupt",true)||!PetService.speechOutputActive())return;
                android.content.SharedPreferences health=getSharedPreferences("voice_health",0);
                health.edit().putInt("barge_interruptions",health.getInt("barge_interruptions",0)+1).apply();
                android.util.Log.i("SolphiviaVoice","BARGE-IN: la sento parlare, la muto e riascolto; intanto detto=\""+heard+"\"");
                if(heard!=null&&!heard.isEmpty())showControlTranscript(heard);
                interruptReading();
            });}
            public void interrupted(String text){main.post(()->{
                if(stopped||wakeGeneration!=generation)return;
                closeWake();
                if(!getSharedPreferences("pet",0).getBoolean("voice_interrupt",true)||!PetService.speechOutputActive())return;
                android.content.SharedPreferences health=getSharedPreferences("voice_health",0);
                health.edit().putInt("speech_interruptions",health.getInt("speech_interruptions",0)+1).apply();
                android.util.Log.i("SolphiviaVoice","INTERRUPT da comando riconosciuto: \""+text+"\"");
                if(text==null||text.isEmpty())health.edit().putInt("barge_interruptions",health.getInt("barge_interruptions",0)+1).apply();
                else showControlTranscript(text);
                interruptReading();
            });}
            public void echo(boolean available,boolean enabled){getSharedPreferences("voice_health",0).edit().putBoolean("aec_available",available).putBoolean("aec_enabled",enabled).apply();}
            public void samples(long count){getSharedPreferences("voice_health",0).edit().putLong("local_samples",count).putInt("command_starts",commandStarts).apply();}
        });localWake.start();
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(stopped)return;
        long now=SystemClock.elapsedRealtime();
        if(listening && now-recognitionStarted>60000L){listenLocally();}
        if(PetService.voiceOutputBusy()){
            if(listening){listening=false;recognitionGeneration++;recognizer.cancel();cancelTranscript();PetService.voiceListening(false);}
            if(getSharedPreferences("pet",0).getBoolean("voice_interrupt",true)&&PetService.speechOutputActive()){
                if(localWake==null)startWake();
                if(localWake!=null)localWake.interruptDuring(PetService.currentSpokenText());
            }else if(localWake!=null)localWake.suspend(true);
            // The next five seconds start only after output has ended and capture is ready.
            if(!session.paused && !followUp.awaitingReply())followUp.replyStarted();
            nextListen=now+650;
        }else if(now>=nextListen){
            if(followUp.awaitingReply()){followUp.replyFinished();session.paused=false;}
            if(session.paused){if(!listening && localWake==null){notifyState("In ascolto locale · Hola Solphivia");startWake();}else if(localWake!=null)localWake.waitForWake();}
            else if(localConversation){
                if(localWake==null){startWake();notifyState("Dialogo in ascolto locale · parla oppure di’ Stop dialogo");}
                if(localWake!=null)localWake.waitForCommand();
            }
            else if(!listening){
                closeWake();listening=true;notifyState(VoiceStatusState.CAPTURE_STARTING_LABEL);recognitionStarted=now;commandStarts++;
                android.util.Log.i("SolphiviaVoice","Ascolto comando avviato (Google)");
                getSharedPreferences("voice_health",0).edit().putInt("command_starts",commandStarts).apply();
                try{startCapture();}
                catch(RuntimeException error){listenLocally();}
            }
        }
        main.postDelayed(this,250);
    }};
    private void notifyState(String state){
        statusText=state;
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("voice_session","Microfono Solphivia",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,20,new Intent(this,VoiceSessionActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,21,new Intent(this,VoiceSessionService.class).setAction(PAUSE),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent interrupt=PendingIntent.getService(this,23,new Intent(this,VoiceSessionService.class).setAction(INTERRUPT),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off=PendingIntent.getService(this,22,new Intent(this,VoiceSessionService.class).setAction(OFF),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,"voice_session").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Microfono Solphivia attivo").setContentText(state).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"Silenzia la voce",interrupt).build())
                .addAction(new Notification.Action.Builder(null,"Stop dialogo",pause).build())
                .addAction(new Notification.Action.Builder(null,"Spegni microfono",off).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(8,note,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(8,note);
        foregroundReady=true;
    }
    @Override public void onResults(Bundle bundle){
        if(stopped || !listening)return;
        listening=false;voiceStatus.transcriptionStarted();PetService.voiceListening(false);nextListen=SystemClock.elapsedRealtime()+600;
        ArrayList<String> matches=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        VoiceSessionState.Decision decision=session.accept(VoicePhrases.best(matches));
        android.util.Log.i("SolphiviaVoice","Trascritto: \""+decision.text+"\" azione="+decision.action);
        if(decision.action==VoiceSessionState.OFF){stopSelf();return;}
        if(decision.action==VoiceSessionState.PAUSE){restForWake();return;}
        if(decision.action==VoiceSessionState.IGNORE){restForWake();return;}
        session.paused=false;
        if(transcript!=null){
            transcript.show(decision.text);
            if(!transcript.canSubmit()){restForWake();notifyState("Bozza scritta conservata · premi Invia quando vuoi");return;}
        }
        voiceStatus.generationStarted();notifyState(voiceStatus.label());
        followUp.replyStarted();
        long generation=recognitionGeneration;
        // Give the final transcript one visible frame before the normal automatic send.
        main.postDelayed(()->{
            if(stopped || session.paused || generation!=recognitionGeneration)return;
            if(transcript!=null){
                if(!transcript.canSubmit()){restForWake();notifyState("Bozza modificata · premi Invia quando vuoi");return;}
                transcript.finish();transcript=null;
            }
            if(handleReadingCommand(decision.text))return;
            if(!PetService.voiceOutputBusy())deliver(decision.text);
        },350);
        nextListen=SystemClock.elapsedRealtime()+1000;
    }
    @Override public void onError(int error){
        if(stopped || !listening)return;
        android.util.Log.i("SolphiviaVoice","Riconoscimento errore codice "+error);
        listening=false;voiceStatus.error();
        cancelTranscript();
        PetService.voiceListening(false);
        if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS){stopSelf();return;}
        getSharedPreferences("voice_health",0).edit().putInt("command_error",error).apply();
        if((error==SpeechRecognizer.ERROR_NO_MATCH || error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                && followUp.canRetry(SystemClock.elapsedRealtime())){
            nextListen=SystemClock.elapsedRealtime()+150;
            notifyState(VoiceStatusState.CAPTURE_STARTING_LABEL);
        }else listenLocally();
    }

    @Override public void onReadyForSpeech(Bundle params){
        if(stopped || !listening || PetService.voiceOutputBusy())return;
        followUp.captureReady(SystemClock.elapsedRealtime());voiceStatus.captureStarted();notifyState(voiceStatus.label());PetService.voiceListening(true);
    }
    @Override public void onBeginningOfSpeech(){
        if(stopped || !listening)return;
        long now=SystemClock.elapsedRealtime();
        if(!followUp.speechStarted(now)){
            followUp.rest();followUp.captureReady(now);followUp.speechStarted(now);
        }
    }
    @Override public void onRmsChanged(float rms){}
    @Override public void onBufferReceived(byte[] bytes){}
    @Override public void onEndOfSpeech(){if(stopped || !listening)return;voiceStatus.transcriptionStarted();notifyState(voiceStatus.label());}
    @Override public void onPartialResults(Bundle partial){
        if(stopped || !listening || partial==null || transcript==null)return;
        ArrayList<String> matches=partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if(matches!=null && !matches.isEmpty())transcript.show(matches.get(0));
    }
    @Override public void onEvent(int type,Bundle params){}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){cancelTranscript();getSharedPreferences("pet",0).edit().putBoolean("voice_requested",false).commit();stopped=true;listening=false;closeWake();LocalWakeDetector.releaseSessionModel();main.removeCallbacksAndMessages(null);if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}if(instance==this)instance=null;if(!statusText.startsWith("Ascolto interrotto") && !statusText.startsWith("Richiamo locale non disponibile"))statusText="Microfono spento.";PetService.voiceListening(false);stopForeground(true);super.onDestroy();}
}
