package it.solphivia.mobile;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.database.Cursor;
import android.util.Base64;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Home-screen overlay containing Solphivia, her speech bubble, and the chat composer. */
public class PetService extends Service {
    private static boolean internalChatVisible;
    private static PetService activeInstance;
    private boolean overlayAttached;
    static boolean overlayVisible(){PetService service=activeInstance;return service!=null&&!service.destroyed&&service.overlayAttached;}

    static void setInternalChatVisible(boolean visible) {
        internalChatVisible = visible;
        if (activeInstance != null) activeInstance.updateOverlayVisibility();
    }

    static void reloadSpeechPreferences(){if(activeInstance!=null)activeInstance.initializeTts();}

    static boolean prepareSpeechPreview() {
        if(activeInstance==null)return true;
        if(activeInstance.busy)return false;
        activeInstance.setSpeech(activeInstance.speechTarget);
        return true;
    }

    private void updateOverlayVisibility() {
        if (panel == null || destroyed) return;
        if (internalChatVisible && overlayAttached) {
            wm.removeViewImmediate(panel);
            overlayAttached = false;
        } else if (!internalChatVisible && !overlayAttached && Settings.canDrawOverlays(this)) {
            try { wm.addView(panel, lp); overlayAttached = true; }
            catch (RuntimeException error) { stopSelf(); }
        }
        if (!internalChatVisible && overlayAttached && greetWhenVisible) greetOnOpen();
    }

    private void updateOverlayLayout() {
        if (overlayAttached) wm.updateViewLayout(panel, lp);
    }

    /** The overlay window holds focus (and the keyboard) only while the composer is in use:
     *  with focus released, WhatsApp and other apps keep typing normally. */
    private void setOverlayFocusable(boolean focusable) {
        if (lp == null || panel == null) return;
        boolean currently = (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (focusable == currently) return;
        if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { updateOverlayLayout(); } catch (RuntimeException ignored) { }
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input == null || composer == null) return;
        if (focusable) input.showSoftInput(composer, 0);
        else input.hideSoftInputFromWindow(composer.getWindowToken(), 0);
    }

    private void releaseComposerFocus() {
        if (composer == null) return;
        composer.clearFocus();
        setOverlayFocusable(false);
    }
    public static final String ACTION_ANIMATE = "it.solphivia.mobile.action.ANIMATE";
    public static final String ACTION_MODE = "it.solphivia.mobile.action.MODE";
    public static final String ACTION_VOICE_SETTINGS = "it.solphivia.mobile.action.VOICE_SETTINGS";
    public static final String ACTION_GREET = "it.solphivia.mobile.action.GREET";
    private boolean greetWhenVisible;
    static boolean voiceOutputBusy() {
        return MainActivity.voiceBusy() || (activeInstance!=null && (activeInstance.busy || activeInstance.typingTask!=null || activeInstance.ttsPending));
    }
    static boolean speechOutputActive(){return DeviceSpeech.outputBusy()||(activeInstance!=null&&activeInstance.ttsPending);}
    private static volatile String lastSpokenText="";
    static void noteLastSpoken(String text){if(text!=null&&!text.trim().isEmpty())lastSpokenText=text.trim();}
    /** Re-speaks the most recent line from the start: the re-hook when the TTS voice drops. */
    static boolean repeatLastSpeech(Context context){
        if(speechOutputActive())return false;
        String text=lastSpokenText;
        if(text.isEmpty())return false;
        if(activeInstance!=null&&!activeInstance.destroyed){activeInstance.typeSpeech(text);return true;}
        new DeviceSpeech(context.getApplicationContext()).speak(text);
        return true;
    }
    static String currentSpokenText(){return ReadingContinuation.echoContext(DeviceSpeech.outputBusy()?DeviceSpeech.spokenText():activeInstance!=null?activeInstance.speechTarget:"");}
    static void stopReading(){
        if(speechOutputActive())ReadingContinuation.pause();
        DeviceSpeech.interruptAll();MainActivity.stopReading();
        if(activeInstance!=null){activeInstance.currentUtteranceSerial++;activeInstance.pendingMotion="stay";activeInstance.setSpeech(activeInstance.speechTarget);}
    }
    static boolean resumeReading(){
        String text=ReadingContinuation.take();if(text.isEmpty())return false;
        if(MainActivity.resumeReading(text))return true;
        if(activeInstance!=null&&!activeInstance.destroyed){activeInstance.typeSpeech(text);return true;}
        ReadingContinuation.begin(text);ReadingContinuation.pause();return false;
    }
    static boolean voiceOutputEnabled(Context context){return context.getSharedPreferences("pet",0).getBoolean("read_aloud",true);}
    /** Pure Edge TTS on/off switch: muted means she only writes, nothing pauses or queues. */
    static void setVoiceOutputEnabled(Context context,boolean enabled){
        context.getSharedPreferences("pet",0).edit().putBoolean("read_aloud",enabled).apply();
        if(!enabled)stopReading();
    }
    static EditText voiceComposer(){return activeInstance!=null && !internalChatVisible && !activeInstance.destroyed && !activeInstance.busy ? activeInstance.composer : null;}
    static void voiceListening(boolean listening) {
        if(activeInstance==null || voiceOutputBusy())return;
        if(listening){activeInstance.cancelIdleTimers();activeInstance.setAnimation("listen");}
        else {activeInstance.setAnimation("idle");activeInstance.resetIdleTimers();}
    }
    private void greetOnOpen() {
        if (internalChatVisible) { greetWhenVisible=true;return; }
        greetWhenVisible=false;
        if (!busy && GreetingPolicy.claim()) { speechEndMood="wave";typeSpeech(GreetingPolicy.OPENING); }
    }
    public static final String ACTION_INPUT_DONE = "it.solphivia.mobile.action.INPUT_DONE";
    public static final String ACTION_WEATHER_LOCATION = "it.solphivia.mobile.action.WEATHER_LOCATION";
    public static final String EXTRA_ANIMATION = "animation";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SEND_MESSAGE = "send_message";
    public static final String EXTRA_DRAFT_MESSAGE = "draft_message";
    public static final String EXTRA_LATITUDE = "weather_latitude";
    public static final String EXTRA_LONGITUDE = "weather_longitude";
    public static final String EXTRA_PLACE = "weather_place";
    private static final String ACTION_STOP = "it.solphivia.mobile.action.STOP";
    private static final String ACTION_PICK_VOICE = "it.solphivia.mobile.action.VOICE_INPUT";
    private static final String ACTION_PICK_FILES = "it.solphivia.mobile.action.PICK_FILES";
    private static final String ACTION_ATTACHMENTS = "it.solphivia.mobile.action.ATTACHMENTS";
    private static final String EXTRA_URIS = "attachment_uris";
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 180;
    private static final long METEORINA_REFRESH_MS = 5 * 60 * 1000L;
    private static final long WEATHER_FORECAST_REFRESH_MS = 30 * 60 * 1000L;
    private static final long REST_AFTER_MS = 5_000L;
    private static final long SLEEP_AFTER_MS = 5_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService weatherWorker = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile long weatherRevision;
    private final java.util.concurrent.atomic.AtomicBoolean weatherRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private WindowManager wm;
    private FrameLayout panel;
    private PetView pet;
    private WindowManager.LayoutParams lp;
    private ScrollView speechScroll;
    private TextView speech;
    private EditText composer;
    private TextView sendButton;
    private TextView weatherBadge;
    private TextView radarBadge;
    private final ArrayList<String> pendingFiles = new ArrayList<>();
    private TextView filesButton;
    private final Deque<RadarSample> radarHistory = new ArrayDeque<>();
    private String radarLocationKey = "";
    private long lastForecastRefreshElapsed;
    private String speechTarget = "";
    private String pendingMotion = "stay";
    private String animation = "idle";
    private Runnable typingTask;
    private ValueAnimator movementAnimator;
    private Runnable sitIdleTask;
    private Runnable sleepIdleTask;
    private boolean busy;
    private float touchX, touchY;
    private int oldX, oldY;
    private boolean dragged;
    private boolean stoppedRunOnTouchDown;
    private int restingY;
    private boolean imeVisible;
    private String speechEndMood = "idle";
    private boolean ttsInitializationComplete = true;
    private int ttsGeneration;
    private boolean ttsReady;
    private boolean englishAccentActive;
    private boolean ttsPending;
    private boolean speechTypingDone = true;
    private boolean speechFinalized = true;
    private long utteranceSerial;
    private long currentUtteranceSerial;
    private String pendingUtterance;
    private float previousTouchX;
    private long touchRevision;
    private long speechTouchRevision;
    private final Runnable weatherRefreshTask = new Runnable() {
        @Override public void run() {
            if (!getSharedPreferences("weather", 0).getBoolean("enabled", false)) return;
            refreshWeather(false);
            main.postDelayed(this, METEORINA_REFRESH_MS);
        }
    };

    private static final class RadarSample {
        final long timeMs;
        final double nearestEchoKm;
        RadarSample(long timeMs, double nearestEchoKm) {
            this.timeMs = timeMs;
            this.nearestEchoKm = nearestEchoKm;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        VoicePreferences.initialize(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        initializeTts();
    }

    private void initializeTts() {
        // Edge TTS needs no engine initialization: the voice path is immediately ready.
        ttsReady = true; ttsInitializationComplete = true;
        englishAccentActive = getSharedPreferences("pet", 0).getBoolean("english_accent", false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppControls.close(this);
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        startForegroundNotification();
        if (panel == null) createOverlay();
        if (panel == null) return START_NOT_STICKY;
        if (intent!=null && ACTION_GREET.equals(intent.getAction()))greetOnOpen();
        if(intent!=null && ACTION_INPUT_DONE.equals(intent.getAction())){setAnimation("idle");resetIdleTimers();}
        if (intent != null && ACTION_VOICE_SETTINGS.equals(intent.getAction())) {
            initializeTts();
            typeSpeech("Ciao, sono Solphivia. Questa è la mia voce: come stai oggi?");
        }
        if (intent != null && intent.hasExtra(EXTRA_DRAFT_MESSAGE) && !busy)
            composer.setText(intent.getStringExtra(EXTRA_DRAFT_MESSAGE));
        if (intent != null && ACTION_ATTACHMENTS.equals(intent.getAction())) {
            ArrayList<String> uris = intent.getStringArrayListExtra(EXTRA_URIS);
            if (uris != null && !uris.isEmpty()) main.post(() -> sendMessageWithAttachments(uris));
        }
        if (intent != null && ACTION_ANIMATE.equals(intent.getAction()) && typingTask == null && !busy) {
            setAnimation(intent.getStringExtra(EXTRA_ANIMATION));
        }
        if (intent != null && ACTION_MODE.equals(intent.getAction()) && pet != null) {
            String mode = intent.getStringExtra(EXTRA_MODE);
            pet.setCyborgSuit("vj".equals(mode));
            pet.setSolphiviaMode(mode);
            if (typingTask == null && !busy) setAnimation("mode");
        }
        if (intent != null && ACTION_WEATHER_LOCATION.equals(intent.getAction())) {
            double lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN);
            double lon = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN);
            String place = intent.getStringExtra(EXTRA_PLACE);
            if (Double.isFinite(lat) && Double.isFinite(lon)) saveWeatherLocation(lat, lon, place == null ? "Posizione GPS" : place);
        }
        if (intent != null && intent.hasExtra(EXTRA_SEND_MESSAGE) && composer != null) {
            String message = intent.getStringExtra(EXTRA_SEND_MESSAGE);
            if (message != null && !message.trim().isEmpty()) main.post(() -> {
                if (composer != null && !busy) { submitMessage(message, new ArrayList<>(), intent.getBooleanExtra("voice_message",false)); }
            });
        }
        return START_NOT_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pet", "Avatar Solphivia", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PetService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note = new Notification.Builder(this, "pet")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Solphivia è sullo schermo")
                .setContentText("Tocca per aprire le impostazioni")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Chiudi Solphivia", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, note);
    }

    private void createOverlay() {
        int width = dp(PANEL_W), height = dp(PANEL_H);
        // Focusable only while the composer is in use: other apps keep their keyboard.
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        lp.x = getSharedPreferences("pet", 0).getInt("panel_x", Math.max(0, (getResources().getDisplayMetrics().widthPixels - width) / 2));
        lp.y = getSharedPreferences("pet", 0).getInt("panel_y", Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f));
        clampPanel();
        restingY = lp.y;

        panel = new FrameLayout(this);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setBackgroundColor(Color.TRANSPARENT);

        // Bubble tail points from the callout toward Solphivia's mouth.
        BubbleTail tail = new BubbleTail(this);
        FrameLayout.LayoutParams tailLp = new FrameLayout.LayoutParams(dp(50), dp(22), Gravity.TOP | Gravity.LEFT);
        tailLp.leftMargin = dp(176); tailLp.topMargin = dp(25);
        panel.addView(tail, tailLp);

        speechScroll = new ScrollView(this);
        speechScroll.setFillViewport(false);
        speechScroll.setVerticalScrollBarEnabled(false);
        speechScroll.setBackground(shape(0xFFFAF7FC, dp(17), 0xFFE3D8E8));
        speechScroll.setElevation(dp(4));
        speech = new TextView(this);
        speech.setTextColor(0xFF302736);
        speech.setTextSize(13);
        speech.setLineSpacing(dp(1), 1f);
        speech.setPadding(dp(10), dp(7), dp(10), dp(7));
        speechScroll.addView(speech, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(dp(184), dp(66), Gravity.TOP | Gravity.LEFT);
        bubbleLp.leftMargin = dp(5); bubbleLp.topMargin = dp(7);
        panel.addView(speechScroll, bubbleLp);

        pet = new PetView(this);
        pet.setContentDescription("Solphivia: trascina per spostare avatar, fumetto e chat");
        FrameLayout.LayoutParams petLp = new FrameLayout.LayoutParams(dp(90), dp(112), Gravity.TOP | Gravity.LEFT);
        petLp.leftMargin = dp(194); petLp.topMargin = 0;
        panel.addView(pet, petLp);
        String activeMode = getSharedPreferences("pet", 0).getString("active_mode", "prof");
        pet.setCyborgSuit("vj".equals(activeMode));
        pet.setSolphiviaMode(activeMode);
        pet.setWeatherLook(getSharedPreferences("weather",0).getString("avatar_look","none"));
        if (getSharedPreferences("pet", 0).getBoolean("costume_override", false)) pet.setClassicCyborgSuit(true);
        pet.setOnTouchListener(this::onPetTouch);

        PowerButton power=new PowerButton(this);
        FrameLayout.LayoutParams powerLp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.TOP|Gravity.LEFT);
        powerLp.leftMargin=dp(7);powerLp.topMargin=dp(88);panel.addView(power,powerLp);
        TextView memoryAction=createComposerAction("☰", "Apri menu: memoria, voce e icona Home");
        FrameLayout.LayoutParams menuLp=new FrameLayout.LayoutParams(dp(36),dp(34),Gravity.TOP|Gravity.LEFT);
        menuLp.leftMargin=dp(57);menuLp.topMargin=dp(88); panel.addView(memoryAction,menuLp);
        memoryAction.setOnClickListener(v -> openInputActivity(CompanionInputActivity.ACTION_SETTINGS));

        FrameLayout composerRow = new FrameLayout(this);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(dp(292), dp(46), Gravity.TOP | Gravity.LEFT);
        rowLp.leftMargin = dp(4); rowLp.topMargin = dp(129);
        panel.addView(composerRow, rowLp);

        composer = new EditText(this);
        composer.setSingleLine(true);
        composer.setMaxLines(1);
        composer.setTextSize(14);
        composer.setTextColor(0xFF29232D);
        composer.setHintTextColor(0xFF766D7B);
        composer.setHint(getPackageName().endsWith(".verification") ? "Scrivi a Solphivia · PROVA…" : "Scrivi a Solphivia…");
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setPadding(dp(12), 0, dp(126), 0);
        composer.setBackground(shape(0xFFFCFEFF, dp(23), 0xFF9BE9F1));
        composer.setElevation(dp(5));
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) { sendMessage(); return true; }
            return false;
        });
        composer.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                wakeFromRest(); resetIdleTimers();
                composer.setTextColor(0xFF29232D);
                setOverlayFocusable(true);
            } else setOverlayFocusable(false);
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count > 0) { wakeFromRest(); resetIdleTimers(); }
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        composerRow.addView(composer, new FrameLayout.LayoutParams(-1, -1));

        filesButton = createComposerAction("＋", "Aggiungi PDF, Word, testo o immagini");
        FrameLayout.LayoutParams filesLp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        filesLp.rightMargin = dp(82);
        composerRow.addView(filesButton, filesLp);
        filesButton.setOnClickListener(v -> openInputActivity(ACTION_PICK_FILES));
        filesButton.setOnLongClickListener(v -> { pendingFiles.clear(); updateFilesButton(); setSpeech("Allegati rimossi dalla bozza."); return true; });

        TextView micButton = createComposerAction("🎙", "Detta un messaggio");
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        micLp.rightMargin = dp(43);
        composerRow.addView(micButton, micLp);
        micButton.setOnClickListener(v -> openInputActivity(ACTION_PICK_VOICE));

        sendButton = createComposerAction("➜", "Invia messaggio");
        sendButton.setTextSize(23);
        sendButton.setTypeface(null, Typeface.BOLD);
        FrameLayout.LayoutParams sendLp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        sendLp.rightMargin = dp(3);
        composerRow.addView(sendButton, sendLp);
        sendButton.setOnClickListener(v -> sendMessage());

        speech.setText("");
        speechScroll.setOnClickListener(v -> finishTyping());
        if (Build.VERSION.SDK_INT >= 30) {
            panel.setOnApplyWindowInsetsListener((v, insets) -> {
                int previousY = lp.y;
                int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                int navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                boolean keyboardNowVisible = imeBottom > navBottom;
                if (keyboardNowVisible && !imeVisible) { imeVisible = true; restingY = lp.y; }
                if (keyboardNowVisible) {
                    lp.y = Math.max(0, Math.min(restingY,
                            getResources().getDisplayMetrics().heightPixels - imeBottom - lp.height - dp(8)));
                } else if (imeVisible) {
                    imeVisible = false; lp.y = restingY; clampPanel();
                }
                if (previousY != lp.y) {
                    try { updateOverlayLayout(); } catch (RuntimeException ignored) { }
                }
                return insets;
            });
        }
        try {
            if (!internalChatVisible) { wm.addView(panel, lp); overlayAttached = true; }
            getSharedPreferences("pet", 0).edit().putBoolean("overlay_active", true).apply();
        }
        catch (RuntimeException error) { panel = null; pet = null; stopSelf(); return; }
        panel.requestApplyInsets();
        composer.setOnClickListener(v -> composer.requestFocus());
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        greetOnOpen();
        refreshWeather(false);
        main.removeCallbacks(weatherRefreshTask);
        if (getSharedPreferences("weather", 0).getBoolean("enabled", false)) main.postDelayed(weatherRefreshTask, METEORINA_REFRESH_MS);
    }

    private boolean onPetTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchRevision++;
                if (movementAnimator != null) movementAnimator.cancel();
                stoppedRunOnTouchDown = "run".equals(animation);
                wakeFromRest(); resetIdleTimers();
                touchX = event.getRawX(); previousTouchX = touchX; touchY = event.getRawY(); oldX = lp.x; oldY = lp.y; dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchX, dy = event.getRawY() - touchY;
                // A tiny intentional finger movement should start the full run loop.
                if (Math.hypot(dx, dy) > 1f) dragged = true;
                if (dragged) {
                    cancelIdleTimers();
                    if (!"run".equals(animation)) setAnimation("run");
                    float stepX = event.getRawX() - previousTouchX;
                    if (Math.abs(stepX) > 0.5f) pet.setScaleX(stepX < 0 ? -1f : 1f);
                    previousTouchX = event.getRawX();
                    lp.x = oldX + Math.round(dx); lp.y = oldY + Math.round(dy);
                    clampPanel();
                    try { updateOverlayLayout(); } catch (RuntimeException ignored) { }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragged) {
                    if (!imeVisible) restingY = lp.y;
                    getSharedPreferences("pet", 0).edit().putInt("panel_x", lp.x).putInt("panel_y", restingY).apply();
                    // Keep the run cycle going after release; the next tap on Solphivia stops it.
                    resetIdleTimers();
                } else if (stoppedRunOnTouchDown) {
                    stoppedRunOnTouchDown = false;
                    setAnimation("idle");
                    if (pet != null) pet.setScaleX(1f);
                    resetIdleTimers();
                } else {
                    pet.performClick(); setAnimation("happy");
                    main.postDelayed(() -> { if (!busy && pet != null && "happy".equals(animation)) setAnimation("idle"); resetIdleTimers(); }, 1100);
                }
                stoppedRunOnTouchDown = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                // A canceled pointer sequence does not count as the tap that stops a run.
                if (stoppedRunOnTouchDown && pet != null) setAnimation("run");
                stoppedRunOnTouchDown = false;
                return true;
            default: return false;
        }
    }

    private TextView createComposerAction(String label, String description) {
        TextView button = new TextView(this);
        button.setText(label); button.setTextSize(19); button.setTextColor(0xFF155B66);
        button.setGravity(Gravity.CENTER); button.setContentDescription(description);
        button.setBackground(shape(0xFFDDFBFF, dp(20), 0xFF9BE9F1));
        // Composer elevation is 5dp; actions must be above it to be visible and tappable.
        button.setElevation(dp(7));
        return button;
    }

    private void openInputActivity(String action) {
        releaseComposerFocus();
        if(ACTION_PICK_VOICE.equals(action)) {
            try {startActivity(new Intent(this,VoiceSessionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
            catch(RuntimeException error){setSpeech("Non riesco ad aprire la sessione vocale.");}
            return;
        }
        if (destroyed || (busy && !CompanionInputActivity.ACTION_SETTINGS.equals(action))) return;
        if (ACTION_PICK_VOICE.equals(action)) { setSpeech(VoiceStatusState.CAPTURE_STARTING_LABEL); cancelIdleTimers(); setAnimation("listen"); }
        try { startActivity(new Intent(this, CompanionInputActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException error) { setSpeech("Non riesco ad aprire questa funzione sul telefono."); }
    }

    private void sendMessage() {
        if (busy) return;
        String text = composer.getText().toString().trim();
        if (text.isEmpty() && pendingFiles.isEmpty()) return;
        if (text.isEmpty()) text="Puoi esaminare questi allegati e aiutarmi?";
        releaseComposerFocus();
        submitMessage(text, new ArrayList<>(pendingFiles));
    }

    private void sendMessageWithAttachments(ArrayList<String> uris) {
        if (busy || uris == null || uris.isEmpty()) return;
        for(String uri:uris) if(!pendingFiles.contains(uri) && pendingFiles.size()<5) pendingFiles.add(uri);
        updateFilesButton();
        setSpeech(pendingFiles.size()+" allegati pronti. Scrivi la richiesta e premi ➜. Tieni premuto + per rimuoverli.");
    }

    private void updateFilesButton() {
        if(filesButton!=null) { filesButton.setText(pendingFiles.isEmpty()?"＋":"+"+pendingFiles.size()); filesButton.setContentDescription(pendingFiles.size()+" allegati selezionati. Tieni premuto per rimuoverli."); }
    }

    private void submitMessage(String text, ArrayList<String> attachmentUris) { submitMessage(text, attachmentUris, false); }

    private void submitMessage(String text, ArrayList<String> attachmentUris, boolean voice) {
        if (destroyed) return;
        if(voice)SentVoiceTranscript.hold(composer,text);
        if(attachmentUris.isEmpty()&&VoiceSessionService.handleReadingCommand(text))return;
        ReadingContinuation.clear();
        if(attachmentUris.isEmpty()) {
            String appReply=LocalAppCommands.handle(this,text);
            if(appReply!=null){if(!voice)composer.setText("");typeSpeech(appReply);return;}
            String memoryReply=MemoryCommands.handle(this,text);
            if(memoryReply!=null){if(!voice)composer.setText("");typeSpeech(memoryReply);return;}
        }
        if (attachmentUris.isEmpty() && normalizeCommand(text).equals("memoria")) {
            if(!voice)composer.setText("");
            startActivity(new Intent(this, MemoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (attachmentUris.isEmpty() && normalizeCommand(text).equals("impostazioni voce")) {
            if(!voice)composer.setText("");
            startActivity(new Intent(this, VoiceSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (attachmentUris.isEmpty() && normalizeCommand(text).equals("nuova chat")) {
            getSharedPreferences("chat", 0).edit().remove("history").apply();
            if(!voice)composer.setText(""); setSpeech("Nuova chat pronta."); return;
        }
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        boolean disableWeather = normalized.equals("disattiva meteo") || normalized.equals("spegni meteorina");
        if (!disableWeather && ReviewRules.weatherSetup(text, !attachmentUris.isEmpty())) {
            if(!voice)composer.setText("");
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(), 0);
            openInputActivity(CompanionInputActivity.ACTION_WEATHER_SETUP);
            return;
        }
        if (disableWeather && attachmentUris.isEmpty()) {
            weatherRevision++;
            getSharedPreferences("weather", 0).edit().putBoolean("enabled", false).apply();
            main.removeCallbacks(weatherRefreshTask);
            getSharedPreferences("weather",0).edit().putString("avatar_look","none").apply();
            if (pet != null) pet.setWeatherLook("none");
            if (weatherBadge != null) weatherBadge.setText("🌦 Meteo");
            if (radarBadge != null) radarBadge.setText("Radar —");
            getSharedPreferences("weather",0).edit().putString("radar_status","Meteorina disattivata.").apply();
            if(!voice)composer.setText("");
            setSpeech("Va bene, metto in pausa la Meteorina.");
            return;
        }
        if (attachmentUris.isEmpty() && handleGreetingCommand(text)) {if(voice)SentVoiceTranscript.hold(composer,text);return;}
        if (attachmentUris.isEmpty() && handleVoiceModeCommand(text)) {if(voice)SentVoiceTranscript.hold(composer,text);return;}
        if (attachmentUris.isEmpty() && isShutdownCommand(text)) {AppControls.close(this);return;}

        applyCostumeCommand(text);
        if (!OpenAiChatClient.isConnected(this) && !ProviderConfig.isCustomActive(this)) {
            setSpeech("Collega un modello nel browser o ripassa l'avvio di Solphivia.");
            try {
                startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_CONNECT_CHATGPT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException error) {
                setSpeech("Per collegare un modello, ripassa l'avvio di Solphivia.");
            }
            return;
        }
        cancelIdleTimers();
        if(!voice)composer.setText("");
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(), 0);
        composer.clearFocus();
        busy = true; composer.setEnabled(false); sendButton.setEnabled(false);
        setAnimation("thinking"); setSpeech(attachmentUris.isEmpty() ? VoiceStatusState.THINKING_LABEL : "Sto preparando gli allegati…");
        if (attachmentUris.isEmpty()) {
            dispatchMessage(text, new JSONArray(), voice);
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    JSONArray attachments = readAttachments(attachmentUris);
                    main.post(() -> { if (!destroyed) dispatchMessage(text, attachments); });
                } catch (Exception error) {
                    main.post(() -> {
                        if (destroyed) return;
                        busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
                        setSpeech("Non riesco a leggere uno degli allegati. Prova con PDF, DOCX o immagini più piccole.");
                        resetIdleTimers();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            if (!destroyed) {
                busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
                setSpeech("Il servizio si è fermato prima di leggere l’allegato.");
            }
        }
    }

    private void saveWeatherLocation(double lat, double lon, String place) {
        weatherRevision++;
        getSharedPreferences("weather", 0).edit().putBoolean("enabled", true)
                .putLong("latitude", Double.doubleToLongBits(lat))
                .putLong("longitude", Double.doubleToLongBits(lon))
                .putString("place", place).apply();
        if (weatherBadge != null) weatherBadge.setText("🌦 Aggiorna");
        refreshWeather(true);
        main.removeCallbacks(weatherRefreshTask);
        main.postDelayed(weatherRefreshTask, METEORINA_REFRESH_MS);
    }

    private void refreshWeather(boolean announce) {
        if (destroyed) return;
        android.content.SharedPreferences p = getSharedPreferences("weather", 0);
        if (!p.getBoolean("enabled", false)) return;
        double lat = Double.longBitsToDouble(p.getLong("latitude", Double.doubleToLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong("longitude", Double.doubleToLongBits(Double.NaN)));
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        if (!weatherRunning.compareAndSet(false, true)) {
            if (announce) main.postDelayed(() -> { if (!destroyed) refreshWeather(true); }, 1000);
            return;
        }
        final long revision = weatherRevision;
        final String location = p.getString("place", "la tua località");
        try { weatherWorker.execute(() -> {
            try {
            long nowElapsed = android.os.SystemClock.elapsedRealtime();
            boolean refreshForecast = announce || lastForecastRefreshElapsed == 0 ||
                    nowElapsed - lastForecastRefreshElapsed >= WEATHER_FORECAST_REFRESH_MS;
            if (refreshForecast) try {
                String address = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m&hourly=precipitation_probability,precipitation,weather_code&forecast_days=2&timezone=auto",
                        lat, lon);
                JSONObject root = getWeatherJson(address);
                JSONObject current = root.getJSONObject("current");
                JSONObject hourly = root.getJSONObject("hourly");
                double temp = current.optDouble("temperature_2m", Double.NaN);
                double feels = current.optDouble("apparent_temperature", temp);
                double rain = current.optDouble("precipitation", 0);
                double wind = current.optDouble("wind_speed_10m", 0);
                int code = current.optInt("weather_code", -1);
                boolean day = current.optInt("is_day", 1) == 1;
                int probability = 0;
                int first = 0;
                JSONArray times = hourly.optJSONArray("time");
                JSONArray probabilities = hourly.optJSONArray("precipitation_probability");
                String currentTime = current.optString("time", "");
                if (times != null && probabilities != null) {
                    for (int i = 0; i < times.length(); i++) if (times.optString(i).compareTo(currentTime) >= 0) { first = i; break; }
                    for (int i = first; i < Math.min(first + 6, probabilities.length()); i++) {
                        int value = probabilities.optInt(i, 0);
                        if (value > probability) probability = value;
                    }
                }
                String weatherLook = weatherLook(code, temp, day, rain);
                String description = weatherDescription(code);
                String summary = String.format(Locale.ITALY, "%s: %.0f°, percepiti %.0f°, %s. Pioggia nelle prossime 6 ore: %d%%; vento %.0f km/h. Fonti: Open-Meteo e radar SRI della Protezione Civile.",
                        location, temp, feels, description.toLowerCase(Locale.ITALY), probability, wind);
                main.post(() -> {
                    if (!weatherCurrent(revision)) return;
                    getSharedPreferences("weather",0).edit().putString("avatar_look",weatherLook).apply();
                    if (pet != null) pet.setWeatherLook(weatherLook);
                    if (weatherBadge != null) weatherBadge.setText(String.format(Locale.ROOT, "%s %.0f°", weatherIcon(code), temp));
                    if (announce && !busy && typingTask == null) setSpeech(summary);
                    if (pet != null && ("sit".equals(animation) || "sleep".equals(animation))) setAnimation("idle");
                });
                lastForecastRefreshElapsed = nowElapsed;
            } catch (Exception error) {
                main.post(() -> {
                    if (!weatherCurrent(revision)) return;
                    if (weatherBadge != null) weatherBadge.setText("🌦 Riprova");
                    if (announce && !busy) setSpeech("Non riesco ad aggiornare il meteo. Controlla la connessione e riprova.");
                });
            }
            try {
                if (weatherCurrent(revision)) updateRadarNowcast(lat, lon, revision, location);
            } catch (Exception error) {
                android.util.Log.w("SolphiviaMeteorina", "Aggiornamento radar non riuscito", error);
                main.post(() -> {
                    if (!weatherCurrent(revision)) return;
                    showRadarStatus("Radar !", "Radar non disponibile: " + (error.getMessage()==null ? "errore di rete" : error.getMessage()), revision);
                });
            }
            } finally { weatherRunning.set(false); }
        }); } catch (java.util.concurrent.RejectedExecutionException error) {
            weatherRunning.set(false);
        }
    }

    private boolean weatherCurrent(long revision) {
        return !destroyed && weatherRevision == revision && getSharedPreferences("weather", 0).getBoolean("enabled", false);
    }

    /** Compares successive national radar scans for an explicitly uncertain nowcast. */
    private void updateRadarNowcast(double lat, double lon, long revision, String place) throws Exception {
        String locationKey = String.format(Locale.ROOT, "%.3f,%.3f", lat, lon);
        if (!locationKey.equals(radarLocationKey)) {
            radarHistory.clear();
            radarLocationKey = locationKey;
        }
        long scanTime = RadarClient.latestTime();
        if (!ReviewRules.freshRadar(scanTime, System.currentTimeMillis())) {
            radarHistory.clear(); showRadarStatus("Radar vecchio", "L’ultima scansione radar è troppo vecchia. Nessun avviso basato su questi dati.", revision); return;
        }
        if (scanTime <= 0 || (!radarHistory.isEmpty() && radarHistory.peekLast().timeMs >= scanTime)) return;
        double nearestKm = RadarClient.read(lat, lon, scanTime).nearestKm;
        if (!weatherCurrent(revision)) return;
        radarHistory.addLast(new RadarSample(scanTime, nearestKm));
        while (radarHistory.size() > 4) radarHistory.removeFirst();
        String clock = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new java.util.Date(scanTime));
        showRadarStatus("Radar " + clock, "Scansione SRI delle " + clock + ". " +
                (Double.isFinite(nearestKm) ? "Precipitazione stimata ≥1 mm/h a circa " + Math.round(nearestKm) + " km."
                : "Nessun pixel ≥1 mm/h rilevato entro 60 km; non garantisce assenza di pioggia o copertura completa."), revision);
        if (weatherCurrent(revision)) evaluateRadarHistory(revision, place);
    }

    private void showRadarStatus(String label, String detail, long revision) {
        main.post(() -> {
            if (!weatherCurrent(revision)) return;
            getSharedPreferences("weather",0).edit().putString("radar_status",detail).apply();
            if (radarBadge != null) { radarBadge.setText(label); radarBadge.setContentDescription(detail); }
        });
    }

    private void evaluateRadarHistory(long revision, String place) {
        if (radarHistory.size() < 3) return;
        RadarSample[] samples = radarHistory.toArray(new RadarSample[0]);
        RadarSample first = samples[samples.length - 3];
        RadarSample middle = samples[samples.length - 2];
        RadarSample last = samples[samples.length - 1];
        long span = last.timeMs - first.timeMs;
        if (span < 8 * 60 * 1000L || span > 25 * 60 * 1000L) return;
        if (!Double.isFinite(first.nearestEchoKm) || !Double.isFinite(middle.nearestEchoKm) || !Double.isFinite(last.nearestEchoKm)) return;
        if (!ReviewRules.nearbyEcho(first.nearestEchoKm) || !ReviewRules.nearbyEcho(middle.nearestEchoKm) ||
                !ReviewRules.nearbyEcho(last.nearestEchoKm)) return;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs = getSharedPreferences("weather", 0);
        if (now - prefs.getLong("radar_last_alert", 0L) < 45 * 60 * 1000L) return;
        String message = String.format(Locale.ITALY,
                "Meteorina: rilevamento sperimentale di echi radar DPC entro circa %d km da %s in tre scansioni. Non determina direzione o arrivo della pioggia.",
                Math.round(last.nearestEchoKm), place);
        main.post(() -> {
            if (!weatherCurrent(revision) || !ReviewRules.freshRadar(last.timeMs, System.currentTimeMillis())) return;
            prefs.edit().putLong("radar_last_alert", now).apply();
            if (weatherBadge != null) weatherBadge.setContentDescription("Echi radar nelle vicinanze; direzione non determinata");
            if (!busy && typingTask == null) setSpeech(message);
            postRadarNotification(message);
        });
    }

    private void postRadarNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel("meteorina", "Avvisi Meteorina", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 22,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "meteorina")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Solphivia Meteorina")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setAutoCancel(true).build();
        try { manager.notify(22, notification); }
        catch (SecurityException error) { android.util.Log.i("SolphiviaMeteorina", "Notifiche non autorizzate", error); }
    }

    private JSONObject getWeatherJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private String weatherLook(int code, double temp, boolean day, double rain) {
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95 || rain > 0.1) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86 || temp < 10) return "cold";
        if (day && temp >= 27 && code == 0) return "sun";
        return "none";
    }
    private String weatherIcon(int code) {
        if (code >= 95) return "⛈️";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "☂️";
        if (code == 0) return "☀️";
        if (code <= 2) return "🌤️";
        return "☁️";
    }
    private String weatherDescription(int code) {
        if (code == 0) return "Sereno";
        if (code == 1) return "Prevalentemente sereno";
        if (code == 2) return "Parzialmente nuvoloso";
        if (code == 3) return "Nuvoloso";
        if (code == 45 || code == 48) return "Nebbia";
        if (code >= 51 && code <= 57) return "Pioviggine";
        if (code >= 61 && code <= 67) return "Pioggia";
        if (code >= 71 && code <= 77) return "Neve";
        if (code >= 80 && code <= 82) return "Rovesci";
        if (code >= 95) return "Temporale";
        return "Condizioni variabili";
    }

    private JSONArray readAttachments(ArrayList<String> uris) throws Exception {
        return AttachmentReader.read(this, uris, new long[]{0});
    }

    private void dispatchMessage(String text, JSONArray attachments) { dispatchMessage(text, attachments, false); }

    private void dispatchMessage(String text, JSONArray attachments, boolean voice) {
        if (destroyed) return;
        String storedText = text;
        if (attachments.length() > 0) {
            StringBuilder names = new StringBuilder("\n[Allegati: ");
            for (int i=0;i<attachments.length();i++) {
                JSONObject item = attachments.optJSONObject(i);
                if (item != null) { if (i > 0) names.append(", "); names.append(item.optString("filename", "file")); }
            }
            storedText += names.append(']').toString();
        }
        JSONArray history = loadHistory();
        try {
            history.put(new JSONObject().put("role", "user").put("content", storedText)
                    .put("attachments", AttachmentReader.references(attachments)));
            while (history.length() > 16) history.remove(0);
        } catch (Exception error) {
            busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
            setSpeech("Non riesco a conservare gli allegati. Riprova."); return;
        }
        if (ProviderConfig.isCustomActive(this)) {
            CustomProviderClient.respond(this, worker, history, voice, new OpenAiChatClient.ChatCallback() {
                @Override public void onSuccess(String reply, String mood, String motion, String mode) {
                    main.post(() -> {
                        if (destroyed) return;
                        getSharedPreferences("chat", 0).edit().putString("history", history.toString()).apply();
                        if(!voice){pendingFiles.clear();updateFilesButton();}
                        busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
                        applyContextualMode(PetService.this, mode, pet);
                        pendingMotion = preserveRunIntent(motion);
                        saveMessage("assistant", reply);
                        speechEndMood = mood == null ? "idle" : mood;
                        typeSpeech(reply);
                    });
                }
                @Override public void onError(String message) { customError(message, voice); }
            });
            return;
        }
        OpenAiChatClient.respond(this, worker, history, attachments, voice, new OpenAiChatClient.ChatCallback() {
            @Override public void onSuccess(String reply, String mood, String motion, String mode) {
                main.post(() -> {
                    if (destroyed) return;
                    getSharedPreferences("chat", 0).edit().putString("history", history.toString()).apply();
                    if(!voice){pendingFiles.clear();updateFilesButton();}
                    busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
                    applyContextualMode(PetService.this, mode, pet);
                    pendingMotion = preserveRunIntent(motion);
                    saveMessage("assistant", reply);
                    speechEndMood = mood == null ? "idle" : mood;
                    typeSpeech(reply);
                });
            }
            @Override public void onError(String message) {
                main.post(() -> {
                    if (destroyed) return;
                    busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
                    SentVoiceTranscript.release(composer);composer.setText(text);
                    setAnimation("concerned"); setSpeech(message);
                    resetIdleTimers();
                });
            }
        });
    }

    /** Error handling shared by the custom-provider path (same UX as the ChatGPT path). */
    private void customError(String message, boolean voice) {
        main.post(() -> {
            if (destroyed) return;
            busy = false; composer.setEnabled(true); sendButton.setEnabled(true);
            setAnimation("concerned"); setSpeech(message);
            resetIdleTimers();
        });
    }

    private void typeSpeech(String text) {
        if (destroyed) return;
        if (typingTask != null) { main.removeCallbacks(typingTask); typingTask = null; }
        EdgeTts.stop();
        pendingUtterance = null;
        currentUtteranceSerial = ++utteranceSerial;
        speechTouchRevision = touchRevision;
        ttsPending = true;
        speechTypingDone = false;
        speechFinalized = false;
        cancelIdleTimers();
        speechTarget = text == null ? "" : text;
        speech.setText("");
        setAnimation("talk");
        if (ttsReady) startTtsUtterance(speechTarget, currentUtteranceSerial);
        else if (!ttsInitializationComplete) pendingUtterance = speechTarget;
        else { ttsPending = false; pendingUtterance = null; }
        int[] index = {0};
        typingTask = new Runnable() {
            @Override public void run() {
                if (index[0] >= speechTarget.length()) {
                    typingTask = null;
                    speechTypingDone = true;
                    finishSpokenLine();
                    return;
                }
                int end = index[0] + 1;
                if (Character.isHighSurrogate(speechTarget.charAt(index[0])) &&
                        end < speechTarget.length() && Character.isLowSurrogate(speechTarget.charAt(end))) end++;
                speech.append(speechTarget.substring(index[0], end)); index[0] = end;
                speechScroll.post(() -> speechScroll.fullScroll(View.FOCUS_DOWN));
                long delay = 62;
                char c = speechTarget.charAt(end - 1);
                if (c == '.' || c == '!' || c == '?' || c == '…') delay = 240;
                else if (c == ',' || c == ';' || c == ':') delay = 145;
                main.postDelayed(this, delay);
            }
        };
        typingTask.run();
    }

    private void finishTyping() {
        if (typingTask == null) return;
        main.removeCallbacks(typingTask); typingTask = null; speech.setText(speechTarget);
        speechTypingDone = true;
        finishSpokenLine();
    }

    private void finishSpokenLine() {
        if (!speechTypingDone || ttsPending || speechFinalized) return;
        speechFinalized = true;
        String endMood = speechEndMood;
        speechEndMood = "idle";
        final String motion = pendingMotion;
        pendingMotion = "stay";
        final long revision = touchRevision;
        if (speechTouchRevision != revision || "run".equals(animation)) { resetIdleTimers(); return; }
        if (endMood == null || endMood.equals("idle")) setAnimation("idle");
        else {
            setAnimation(endMood);
            long animationHoldMs = ("nod".equals(endMood) || "explain".equals(endMood) || "celebrate".equals(endMood)) ? 1800L : "wave".equals(endMood) ? 1100L : 900L;
            main.postDelayed(() -> { if (!busy && pet != null && typingTask == null && touchRevision == revision && endMood.equals(animation)) setAnimation("idle"); }, animationHoldMs);
        }
        main.postDelayed(() -> { if (pet != null && touchRevision == revision && typingTask == null && !ttsPending) moveOverlay(motion); }, 300);
        resetIdleTimers();
    }

    private void setSpeech(String text) {
        if (destroyed) return;
        if (typingTask != null) { main.removeCallbacks(typingTask); typingTask = null; }
        EdgeTts.stop();
        pendingUtterance = null; ttsPending = false; speechTypingDone = true; speechFinalized = true;
        speechTarget = text; speech.setText(text); setAnimation("idle"); resetIdleTimers();
    }

    private boolean handleGreetingCommand(String text) {
        String value = normalizeCommand(text).replaceAll("[.!?,;:]+", " ").replaceAll("\\s+", " ").trim();
        if (!(VoicePhrases.greeting(text) || value.equals("saluta") || value.equals("fai ciao") || value.equals("saluta con la mano") ||
                value.equals("ciao con la mano") || value.equals("saluta utente"))) return false;
        composer.setText("");
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(), 0);
        speechEndMood = "wave";
        pendingMotion = "stay";
        typeSpeech("Cosa posso fare per te?");
        return true;
    }

    private long readingToken;
    private final java.util.Map<String,Integer> readingOffsets=new java.util.HashMap<>();
    private void startTtsUtterance(String text, long serial) {
        if (!getSharedPreferences("pet", 0).getBoolean("read_aloud", true)) { ttsPending = false; finishSpokenLine(); return; }
        if (text == null) { ttsPending = false; finishSpokenLine(); return; }
        String spokenText = SpeechText.prepare(text,englishAccentActive,getSharedPreferences("pet",0).getBoolean("clear_italian",true));
        if (spokenText.isEmpty()) { ttsPending = false; finishSpokenLine(); return; }
        readingToken=ReadingContinuation.begin(spokenText);
        lastSpokenText=spokenText;
        String voice = getSharedPreferences("pet",0).getBoolean("english_accent",false) ? EdgeTts.ENGLISH_VOICE : EdgeTts.DEFAULT_VOICE;
        int ratePercent = getSharedPreferences("pet",0).getInt("voice_rate_percent",100);
        EdgeTts.speak(getApplicationContext(), spokenText, voice, ratePercent, new EdgeTts.Listener() {
            @Override public void onDone() { finishTtsUtterance("solphivia-" + serial, false); }
            @Override public void onError(String message) {
                android.util.Log.i("SolphiviaVoice", "EdgeTts errore: " + message + " — riga in pausa, riaggancia con «Ripeti l'ultima frase»");
                finishTtsUtterance("solphivia-" + serial, true);
            }
        });
    }

    private boolean handleVoiceModeCommand(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim();
        boolean english;
        if (normalized.equals("voce inglese") || normalized.equals("usa voce inglese")
                || normalized.equals("attiva voce inglese") || normalized.equals("accento inglese")
                || normalized.equals("prova accento inglese")) {
            english = true;
        } else if (normalized.equals("voce italiana") || normalized.equals("usa voce italiana")
                || normalized.equals("attiva voce italiana") || normalized.equals("accento italiano")) {
            english = false;
        } else return false;

        getSharedPreferences("pet", 0).edit().putBoolean("english_accent", english).apply();
        englishAccentActive = english;
        EdgeTts.stop();
        composer.setText("");
        typeSpeech(english ? "Accento inglese attivato. Dimmi una frase breve." : "Voce italiana attivata.");
        return true;
    }

    private void finishTtsUtterance(String id, boolean failed) {
        main.post(() -> {
            if (destroyed) return;
            if (id == null || !id.equals("solphivia-" + currentUtteranceSerial)) return;
            ttsPending = false;
            if (failed) ReadingContinuation.pause(readingToken); // keep the line for "Riattiva la voce"
            else ReadingContinuation.complete(readingToken);
            finishSpokenLine();
        });
    }

    private void applyCostumeCommand(String text) {
        if (applyModeCommand(this, text, pet)) return;
        Boolean cyborg = requestedCyborgSuit(text);
        if (cyborg != null) {
            getSharedPreferences("pet", 0).edit().putBoolean("costume_override", cyborg).apply();
            if (pet != null) pet.setClassicCyborgSuit(cyborg);
            getSharedPreferences("pet", 0).edit().putBoolean("cyborg_suit", cyborg).apply();
        }
    }

    public static boolean isShutdownCommand(String text) {return SessionCommands.close(text);}

    public static Boolean requestedCyborgSuit(String text) {
        String value = normalizeCommand(text);
        boolean restore = value.contains("originale") || value.contains("normale") || value.contains("look base") ||
                value.contains("togli") || value.contains("rimuovi") || value.contains("ripristina");
        boolean clothing = value.contains("vestit") || value.contains("abito") || value.contains("tuta") ||
                value.contains("look") || value.contains("armatura");
        boolean cyborg = value.contains("cyborg") || value.contains("automan") || value.contains("mutaforma") || value.contains("armatura");
        boolean action = value.contains("indossa") || value.contains("metti") || value.contains("trasform") ||
                value.contains("cambia") || value.contains("vestit") || value.contains("look") ||
                value.contains("abito") || value.contains("tuta") || value.contains("ripristina") || value.contains("togli");
        if (restore && (clothing || action)) return false;
        if (cyborg && (action || value.equals("cyborg") || value.equals("automan"))) return true;
        if (clothing && action) return true;
        return null;
    }

    private static String normalizeCommand(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.toLowerCase(Locale.ITALIAN).replace('’', '\''), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim();
    }

    /** Resolve short mode aliases without mistaking unrelated mentions for a mode change. */
    public static String requestedModeCommand(String text) {
        String value = normalizeCommand(text).replaceAll("[.!?,;:]+", " ").replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) return null;
        if (value.equals("auto") || value.equals("automatico") || value.equals("scegli tu") ||
                value.equals("modalita auto") || value.equals("modalita automatica") ||
                value.equals("scegli tu la modalita") || value.equals("fai tu") ||
                value.equals("lascia scegliere a te")) return "auto";
        if (value.equals("meteorina") || value.equals("modalita meteorina") || value.equals("look meteorina") || value.equals("passa a meteorina")) return "meteorina";
        if (value.equals("prof") || value.equals("proffffff") || value.equals("professoressa") || value.equals("modalita prof") ||
                value.equals("modalita proffffff") || value.startsWith("modalita prof ") ||
                value.equals("modalita professoressa") || value.equals("passa a prof") ||
                value.startsWith("passa a prof ") || value.equals("mettiti da prof") ||
                value.equals("look prof") || value.startsWith("prof ")) return "prof";
        if (value.equals("vj") || value.equals("visual jockey") || value.equals("modalita vj") ||
                value.equals("modalita visual jockey") || value.equals("passa a vj") ||
                value.startsWith("modalita vj ") || value.startsWith("visual jockey ") ||
                value.startsWith("passa a vj ") || value.equals("look vj") || value.startsWith("vj ")) return "vj";
        if (value.equals("codex") || value.equals("codex ultra ultra work") || value.equals("modalita codex") ||
                value.equals("modalita codex ultra ultra work") || value.startsWith("modalita codex ") ||
                value.equals("passa a codex") || value.startsWith("passa a codex ") ||
                value.equals("look codex") || value.startsWith("codex ")) return "codex";
        return null;
    }

    /** Explicit aliases pin a mode; "auto" lets the model choose one from the conversation. */
    public static boolean applyModeCommand(Context context, String text, PetView localView) {
        String mode = requestedModeCommand(text);
        if (mode == null) return false;
        context.getSharedPreferences("pet", 0).edit().putBoolean("costume_override", false).apply();
        context.getSharedPreferences("pet", 0).edit().putString("mode_preference", mode).apply();
        if (!"auto".equals(mode)) applyActiveMode(context, mode, localView);
        return true;
    }

    /** Use the model's context tag only while the user has left automatic mode enabled. */
    public static void applyContextualMode(Context context, String mode, PetView localView) {
        if (context.getSharedPreferences("pet", 0).getBoolean("costume_override", false)) return;
        if (mode == null || !(mode.equals("prof") || mode.equals("vj") || mode.equals("codex") || mode.equals("meteorina"))) return;
        if (!"auto".equals(context.getSharedPreferences("pet", 0).getString("mode_preference", "auto"))) return;
        android.content.SharedPreferences prefs=context.getSharedPreferences("pet",0);
        if (mode.equals(prefs.getString("active_mode","prof"))) return;
        long now=System.currentTimeMillis(),last=prefs.getLong("context_mode_changed_at",0);
        if (now>=last && now-last<20000L) return;
        prefs.edit().putLong("context_mode_changed_at",now).apply();
        applyActiveMode(context, mode, localView);
    }

    private static void applyActiveMode(Context context, String mode, PetView localView) {
        boolean luminousVj = "vj".equals(mode);
        context.getSharedPreferences("pet", 0).edit().putString("active_mode", mode)
                .putBoolean("cyborg_suit", luminousVj).apply();
        if (localView != null) {
            localView.setCyborgSuit(luminousVj);
            localView.setSolphiviaMode(mode);
            localView.setWeatherLook(context.getSharedPreferences("weather",0).getString("avatar_look","none"));
            localView.setAnimation("mode");
        }
        if (context.getSharedPreferences("pet", 0).getBoolean("overlay_active", false)) {
            Intent intent = new Intent(context, PetService.class).setAction(ACTION_MODE).putExtra(EXTRA_MODE, mode);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
        }
    }

    private void wakeFromRest() {
        if ("sit".equals(animation) || "sleep".equals(animation)) setAnimation("idle");
    }

    private void cancelIdleTimers() {
        if (sitIdleTask != null) main.removeCallbacks(sitIdleTask);
        if (sleepIdleTask != null) main.removeCallbacks(sleepIdleTask);
        sitIdleTask = null; sleepIdleTask = null;
    }

    private void resetIdleTimers() {
        cancelIdleTimers();
        if (pet == null || busy || typingTask != null || ttsPending || "run".equals(animation)) return;
        sitIdleTask = () -> {
            sitIdleTask = null;
            if (pet == null || busy || typingTask != null || ttsPending || "run".equals(animation)) return;
            setAnimation("sit");
            sleepIdleTask = () -> {
                sleepIdleTask = null;
                if (pet != null && !busy && typingTask == null && !ttsPending && "sit".equals(animation)) setAnimation("sleep");
            };
            main.postDelayed(sleepIdleTask, SLEEP_AFTER_MS);
        };
        main.postDelayed(sitIdleTask, REST_AFTER_MS);
    }

    private void setAnimation(String command) {
        if (pet != null && command != null) {
            String name = command.startsWith("run-") ? "run" : command;
            if (pet.setAnimation(name)) animation = name;
        }
    }

    private String preserveRunIntent(String motion) {
        JSONArray history = loadHistory();
        if (history.length() < 2) return motion;
        JSONObject latest = history.optJSONObject(history.length() - 1);
        if (latest == null || !"user".equals(latest.optString("role"))) return motion;
        String answer = latest.optString("content").trim().toLowerCase(java.util.Locale.ROOT);
        String direction = answer.contains("sinistra") ? "run-left" : answer.contains("destra") ? "run-right" : null;
        if (direction == null) return motion;
        for (int i = history.length() - 2; i >= 0; i--) {
            JSONObject item = history.optJSONObject(i);
            if (item == null || !"user".equals(item.optString("role"))) continue;
            String prior = item.optString("content").toLowerCase(java.util.Locale.ROOT);
            if (prior.contains("corri") || prior.contains("correre") || prior.contains("di corsa")) return direction;
            break;
        }
        return motion;
    }

    private void moveOverlay(String command) {
        if (panel == null || lp == null || command == null || command.equals("stay")) return;
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int maxX = Math.max(0, m.widthPixels - lp.width), maxY = Math.max(0, m.heightPixels - lp.height);
        int toX = lp.x, toY = lp.y;
        boolean run = command.equals("run-left") || command.equals("run-right");
        switch (command) {
            case "left": case "run-left": toX = 0; break;
            case "right": case "run-right": toX = maxX; break;
            case "center": toX = maxX / 2; toY = maxY / 2; break;
            case "up": toY = 0; break;
            case "down": toY = maxY; break;
            case "near": toX = maxX / 2; toY = maxY; break;
            case "away": toX = maxX / 2; toY = 0; break;
            case "bounce": toY = Math.max(0, lp.y - dp(36)); break;
            default: return;
        }
        int fromX = lp.x, fromY = lp.y;
        final int targetX = toX, targetY = toY;
        if (run) { cancelIdleTimers(); setAnimation("run"); pet.setScaleX(command.equals("run-left") ? -1f : 1f); }
        else setAnimation("look");
        movementAnimator = ValueAnimator.ofFloat(0f, 1f);
        movementAnimator.setDuration(run ? 1050 : 750);
        movementAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        movementAnimator.addUpdateListener(a -> {
            if (panel == null) return;
            float t = (float) a.getAnimatedValue();
            lp.x = Math.round(fromX + (targetX - fromX) * t);
            lp.y = command.equals("bounce") ? fromY - Math.round((float) Math.sin(Math.PI * t) * dp(36))
                    : Math.round(fromY + (targetY - fromY) * t);
            clampPanel();
            try { updateOverlayLayout(); } catch (RuntimeException ignored) { }
        });
        movementAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) { movementAnimator = null; return; }
                if (pet != null) {
                    if (run) { setAnimation("run"); pet.setScaleX(command.equals("run-left") ? -1f : 1f); }
                    else { setAnimation("idle"); pet.setScaleX(1f); }
                }
                if (lp != null) getSharedPreferences("pet", 0).edit().putInt("panel_x", lp.x).putInt("panel_y", lp.y).apply();
                resetIdleTimers();
                movementAnimator = null;
            }
        });
        movementAnimator.start();
    }

    private void saveMessage(String role, String body) {
        JSONArray history = loadHistory();
        try { history.put(new JSONObject().put("role", role).put("content", body)); } catch (Exception ignored) { }
        while (history.length() > 16) history.remove(0);
        getSharedPreferences("chat", 0).edit().putString("history", history.toString()).apply();
    }

    private JSONArray loadHistory() {
        try { return new JSONArray(getSharedPreferences("chat", 0).getString("history", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void clampPanel() {
        if (lp == null) return;
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        lp.x = Math.max(0, Math.min(lp.x, m.widthPixels - lp.width));
        lp.y = Math.max(0, Math.min(lp.y, m.heightPixels - lp.height));
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static class BubbleTail extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        BubbleTail(Context context) { super(context); paint.setColor(0xFFFAF7FC); }
        @Override protected void onDraw(Canvas canvas) {
            path.reset(); path.moveTo(0, 0); path.lineTo(getWidth(), getHeight() / 2f); path.lineTo(0, getHeight()); path.close();
            canvas.drawPath(path, paint);
        }
    }

    public static void animate(Context context, String command) {
        Intent intent = new Intent(context, PetService.class).setAction(ACTION_ANIMATE).putExtra(EXTRA_ANIMATION, command);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (panel != null) { clampPanel(); restingY = lp.y; updateOverlayLayout(); }
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (activeInstance == this) activeInstance = null;
        weatherRevision++;
        getSharedPreferences("pet", 0).edit().putBoolean("overlay_active", false).apply();
        main.removeCallbacksAndMessages(null);
        cancelIdleTimers();
        if (typingTask != null) main.removeCallbacks(typingTask);
        if (movementAnimator != null) {
            movementAnimator.removeAllUpdateListeners(); movementAnimator.removeAllListeners();
            movementAnimator.cancel(); movementAnimator = null;
        }
        OpenAiChatClient.cancelRequests(worker);
        worker.shutdownNow();
        weatherWorker.shutdownNow();
        EdgeTts.stop();
        if (pet != null) pet.pause();
        if (panel != null) { if (overlayAttached) { try { wm.removeView(panel); } catch (IllegalArgumentException ignored) { } } overlayAttached = false; panel = null; pet = null; }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
