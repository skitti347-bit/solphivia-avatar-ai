package it.solphivia.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run onboarding: the avatar takes the foreground and asks, with menus
 * styled on the tek-cyber electric-blue/fuchsia look, how the user wants to
 * start her — ChatGPT as before, OpenRouter, an OpenAI-compatible endpoint or
 * a local LLM on their PC. Ends with microphone and overlay permissions.
 */
public class OnboardingActivity extends Activity {
    // Tek-cyber palette
    private static final int BG = Color.rgb(10, 14, 28);
    private static final int PANEL = Color.rgb(19, 26, 46);
    private static final int PANEL_EDGE = Color.rgb(42, 53, 84);
    private static final int BLUE = Color.rgb(0, 229, 255);
    private static final int FUCHSIA = Color.rgb(255, 46, 200);
    private static final int TEXT = Color.rgb(234, 242, 255);
    private static final int TEXT_DIM = Color.rgb(143, 163, 200);
    private static final int INPUT_BG = Color.rgb(15, 20, 36);

    private static final int REQUEST_MIC = 91;
    private static final String OPENROUTER_BASE = "https://openrouter.ai/api/v1";

    private LinearLayout root;
    private PetView pet;
    private String chosenType = "none";
    private boolean chatGptAuthorizing;
    private LinearLayout suggestionsBox;
    private EditText modelField;
    private java.util.List<String> modelCatalog = new java.util.ArrayList<>();
    private String catalogKey = "";
    private final java.util.ArrayList<Runnable> pendingFetches = new java.util.ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean changeModel = getIntent() != null && getIntent().getBooleanExtra("change_model", false);
        if (getSharedPreferences(ProviderConfig.PREFS, 0).getBoolean(ProviderConfig.ONBOARDING_DONE, false) && !changeModel) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        root.setPadding(pad, pad + 24, pad, pad);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        scroller.addView(root);
        setContentView(scroller);
        ScreenInsets.apply(this, root);
        showWelcome();
    }

    private void showWelcome() {
        root.removeAllViews();
        pet = new PetView(this);
        pet.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(230)));
        root.addView(pet);
        pet.setAnimation("idle");
        main.postDelayed(() -> { if (pet != null && chosenType.equals("none")) pet.setAnimation("wave"); }, 1200);

        TextView title = new TextView(this);
        title.setText("Solphivia");
        title.setTextSize(34);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT);
        title.setShadowLayer(18, 0, 0, BLUE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, dp(4));
        root.addView(title);

        TextView question = new TextView(this);
        question.setText("In che modo mi vuoi avviare?");
        question.setTextSize(16);
        question.setTextColor(TEXT_DIM);
        question.setGravity(Gravity.CENTER);
        question.setPadding(0, 0, 0, dp(16));
        root.addView(question);

        root.addView(optionCard("ChatGPT", "Accedi con il tuo account, come finora",
                v -> showChatGptStep()));
        root.addView(optionCard("OpenRouter", "Una chiave, centinaia di modelli nel cloud",
                v -> showConfigStep("openrouter")));
        root.addView(optionCard("OpenAI compatibile", "Base URL e chiave: vLLM, Groq, proxy tuoi",
                v -> showConfigStep("compatible")));
        root.addView(optionCard("LLM locale sul PC", "LM Studio o Ollama nella stessa rete Wi-Fi, senza cloud",
                v -> showConfigStep("local")));
    }

    private LinearLayout optionCard(String titleText, String subtitleText, View.OnClickListener action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), PANEL_EDGE);
        card.setBackground(background);
        int pad = dp(14);
        int margin = dp(6);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, margin, 0, margin);
        card.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(subtitleText);
        subtitle.setTextSize(13);
        subtitle.setTextColor(TEXT_DIM);
        subtitle.setPadding(0, dp(2), 0, 0);
        card.addView(subtitle);

        card.setOnClickListener(v -> {
            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(Color.rgb(26, 36, 64));
            pressed.setCornerRadius(dp(18));
            pressed.setStroke(dp(1), BLUE);
            card.setBackground(pressed);
            action.onClick(v);
        });
        return card;
    }

    @Override public void onBackPressed() {
        // La scelta del collegamento è obbligata: indietro non esce dall'onboarding.
    }

    private void showChatGptStep() {
        chosenType = "chatgpt";
        root.removeAllViews();
        if (pet != null) pet.pause();
        root.addView(backHeader());
        root.addView(stepTitle("Collega ChatGPT"));
        root.addView(stepText("Solphivia si collega al tuo account ChatGPT dal browser. Niente chiavi da copiare: apro la pagina di accesso e torno qui da sola. La chat resta separata dall'app ChatGPT."));

        Button button = primaryButton("Apri accesso ChatGPT");
        button.setOnClickListener(v -> {
            button.setEnabled(false);
            setStatus("Apro il browser per l'accesso ChatGPT…");
            chatGptAuthorizing = true;
            OpenAiChatClient.authorize(this, worker, new OpenAiChatClient.AuthCallback() {
                @Override public void onSuccess(String account) {
                    main.post(() -> {
                        chatGptAuthorizing = false;
                        if (!isFinishing()) showPermissionsStep();
                    });
                }

                @Override public void onError(String message) {
                    main.post(() -> {
                        chatGptAuthorizing = false;
                        button.setEnabled(true);
                        if (!isFinishing()) setStatus("Accesso non completato: " + message);
                    });
                }
            });
        });
        root.addView(button);
        root.addView(statusView());
    }

    private void showConfigStep(String type) {
        chosenType = type;
        root.removeAllViews();
        if (pet != null) pet.pause();
        root.addView(backHeader());

        String title;
        if ("openrouter".equals(type)) title = "Collega OpenRouter";
        else if ("local".equals(type)) title = "Collega il PC";
        else title = "Endpoint OpenAI-compatibile";
        root.addView(stepTitle(title));

        TextView hint = new TextView(this);
        if ("openrouter".equals(type)) {
            hint.setText("Crea una chiave su openrouter.ai/keys e incollala qui. Il modello lo scegli dopo, oppure scrivi qui il suo slug (es. deepseek/deepseek-chat).");
        } else if ("local".equals(type)) {
            hint.setText("Sul PC avvia LM Studio (porta 1234) o Ollama (11434) con l'opzione di rete attiva. Scrivi l'indirizzo del computer nella stessa Wi-Fi, es. 192.168.1.50:1234. Le conversazioni restano in casa tua.");
        } else {
            hint.setText("Scrivi il base URL del servizio compatibile con le API OpenAI, la chiave e il modello.");
        }
        hint.setTextSize(14);
        hint.setTextColor(TEXT_DIM);
        hint.setPadding(0, 0, 0, dp(10));
        root.addView(hint);

        final EditText baseUrlField;
        if ("openrouter".equals(type)) {
            baseUrlField = null;
        } else {
            baseUrlField = inputField("local".equals(type) ? "192.168.1.50:1234" : "https://esempio.tuo-servizio.it/v1");
            baseUrlField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            baseUrlField.setText(ProviderConfig.baseUrl(this));
            root.addView(baseUrlField);
        }

        final EditText keyField;
        if ("local".equals(type)) {
            keyField = null;
        } else {
            keyField = inputField("Chiave API");
            keyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            String savedKey = ProviderConfig.apiKey(this);
            if (!savedKey.isEmpty()) keyField.setText(savedKey);
            root.addView(keyField);
        }

        final EditText modelField = inputField("Modello (scrivi per cercare tra quelli disponibili)");
        modelField.setSingleLine(true);
        String savedModel = ProviderConfig.model(this);
        if (!savedModel.isEmpty()) modelField.setText(savedModel);
        root.addView(modelField);
        this.modelField = modelField;

        suggestionsBox = new LinearLayout(this);
        suggestionsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(suggestionsBox);

        final String fetchType = type;
        final EditText baseRef = baseUrlField;
        final EditText keyRef = keyField;
        modelField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int c, int a) {
                showModelSuggestions(modelField, s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        android.text.TextWatcher refetch = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                scheduleModelFetch(fetchType, baseRef == null ? "" : baseRef.getText().toString(),
                        keyRef == null ? "" : keyRef.getText().toString());
            }
        };
        if (baseUrlField != null) baseUrlField.addTextChangedListener(refetch);
        if (keyField != null) keyField.addTextChangedListener(refetch);
        scheduleModelFetch(type, resolvedBaseFor(type, baseUrlField), keyField == null ? "" : keyField.getText().toString());

        Button test = primaryButton("Testa e collega");
        test.setOnClickListener(v -> {
            String base = baseUrlField == null ? "" : baseUrlField.getText().toString().trim();
            String key = keyField == null ? "" : keyField.getText().toString().trim();
            String model = modelField.getText().toString().trim();
            if ("local".equals(type) && base.isEmpty()) { setStatus("Scrivi l'indirizzo del PC (ip:porta)."); return; }
            if (!"local".equals(type) && key.isEmpty()) { setStatus("Serve una chiave API."); return; }
            if (model.isEmpty()) { setStatus("Serve il nome del modello."); return; }
            if (!"local".equals(type) && base.isEmpty() && "compatible".equals(type)) {
                setStatus("Serve il base URL."); return;
            }
            String resolvedBase = "openrouter".equals(type) ? OPENROUTER_BASE : base;
            if ("local".equals(type) && !resolvedBase.startsWith("http")) resolvedBase = "http://" + resolvedBase;
            if ("compatible".equals(type) && !resolvedBase.startsWith("http")) resolvedBase = "https://" + resolvedBase;
            ProviderConfig.save(this, type, resolvedBase, model, key);
            setStatus("Provo la connessione…");
            worker.execute(() -> {
                String error = ProviderConfig.testConnection(this);
                main.post(() -> {
                    if (isFinishing()) return;
                    if (error == null) {
                        setStatus("Collegata a " + ProviderConfig.label(this) + " · " + model);
                        showPermissionsStep();
                    } else {
                        setStatus("Connessione non riuscita: " + error);
                    }
                });
            });
        });
        root.addView(test);
        root.addView(statusView());
    }

    private void showPermissionsStep() {
        if (isFinishing()) return;
        root.removeAllViews();
        if (pet != null) pet.pause();
        root.addView(stepTitle("Ultimo passo"));
        root.addView(stepText("Per parlarti e restare in primo piano, Solphivia ha bisogno del microfono e del permesso di apparire sopra le altre app. Puoi spegnere tutto in ogni momento dal pulsante ⏻."));

        LinearLayout micCard = permissionCard("Microfono", "per ascoltare «Hola Solphivia» e le tue frasi",
                "Attiva il microfono", v ->
                        requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQUEST_MIC));
        root.addView(micCard);

        LinearLayout overlayCard = permissionCard("Primo piano", "per mostrare l'avatar sopra le altre app",
                "Permetti l'overlay", v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    }
                });
        root.addView(overlayCard);

        Button start = primaryButton("Fai partire Solphivia");
        start.setOnClickListener(v -> {
            getSharedPreferences("pet", 0).edit().putBoolean("wake_on_open",
                    checkSelfPermission("android.permission.RECORD_AUDIO")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED).apply();
            finishOnboarding();
        });
        root.addView(start);
    }

    private LinearLayout permissionCard(String titleText, String subtitleText, String buttonText, View.OnClickListener action) {
        LinearLayout card = optionCard(titleText, subtitleText, action);
        TextView actionView = new TextView(this);
        actionView.setText(buttonText);
        actionView.setTextSize(14);
        actionView.setTypeface(null, Typeface.BOLD);
        actionView.setTextColor(BLUE);
        actionView.setPadding(0, dp(8), 0, 0);
        card.addView(actionView);
        return card;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == REQUEST_MIC) setStatus(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? "Microfono attivo." : "Puoi attivare il microfono anche più avanti dalle impostazioni.");
    }

    private String resolvedBaseFor(String type, EditText baseUrlField) {
        if ("openrouter".equals(type)) return OPENROUTER_BASE;
        String base = baseUrlField == null ? "" : baseUrlField.getText().toString().trim();
        if ("local".equals(type) && !base.isEmpty() && !base.startsWith("http")) base = "http://" + base;
        if ("compatible".equals(type) && !base.isEmpty() && !base.startsWith("http")) base = "https://" + base;
        return base;
    }

    private void scheduleModelFetch(final String type, final String base, final String key) {
        final String cacheKey = type + "|" + base + "|" + key.hashCode();
        if (cacheKey.equals(catalogKey)) return;
        for (Runnable pending : pendingFetches) main.removeCallbacks(pending);
        pendingFetches.clear();
        catalogKey = cacheKey;
        Runnable fetch = () -> {
            if (base.isEmpty() && !"openrouter".equals(type)) return;
            worker.execute(() -> {
                final java.util.ArrayList<String> ids = ProviderConfig.fetchModels(type, base, key);
                main.post(() -> {
                    if (isFinishing() || modelField == null) return;
                    modelCatalog = ids;
                    if (!ids.isEmpty() && statusViewAlive()) {
                        setStatus(ids.size() + " modelli disponibili: scrivi per filtrare.");
                    }
                    showModelSuggestions(modelField, modelField.getText().toString());
                });
            });
        };
        pendingFetches.add(fetch);
        main.postDelayed(fetch, 450);
    }

    private boolean statusViewAlive() { return findViewById(android.R.id.text1) != null; }

    private void showModelSuggestions(EditText target, String query) {
        if (suggestionsBox == null) return;
        suggestionsBox.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        for (String id : modelCatalog) {
            if (shown >= 6) break;
            if (!q.isEmpty() && !id.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            if (id.equals(query)) continue;
            suggestionsBox.addView(modelChip(target, id));
            shown++;
        }
    }

    private TextView modelChip(EditText target, final String id) {
        TextView chip = new TextView(this);
        chip.setText(id);
        chip.setTextSize(13);
        chip.setTextColor(0xFF00E5FF);
        chip.setTypeface(Typeface.MONOSPACE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(INPUT_BG);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), PANEL_EDGE);
        chip.setBackground(background);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setClickable(true);
        chip.setOnClickListener(v -> {
            target.setText(id);
            target.setSelection(id.length());
            suggestionsBox.removeAllViews();
            setStatus("Modello scelto: " + id);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(4), 0, 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private LinearLayout backHeader() {
        LinearLayout header = new LinearLayout(this);
        TextView back = new TextView(this);
        back.setText("← Indietro");
        back.setTextSize(15);
        back.setTextColor(BLUE);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> showWelcome());
        header.addView(back);
        return header;
    }

    private TextView stepTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(24);
        view.setTypeface(null, Typeface.BOLD);
        view.setTextColor(TEXT);
        view.setPadding(0, dp(6), 0, dp(8));
        return view;
    }

    private TextView stepText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(TEXT_DIM);
        view.setPadding(0, 0, 0, dp(14));
        return view;
    }

    private EditText inputField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(TEXT);
        field.setHintTextColor(Color.rgb(90, 107, 146));
        field.setTextSize(14);
        GradientDrawable background = new GradientDrawable();
        background.setColor(INPUT_BG);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), PANEL_EDGE);
        field.setBackground(background);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(6));
        field.setLayoutParams(params);
        return field;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(BG);
        GradientDrawable background = new GradientDrawable();
        background.setColor(FUCHSIA);
        background.setCornerRadius(dp(26));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, dp(12), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView statusView() {
        TextView status = new TextView(this);
        status.setId(android.R.id.text1);
        status.setTextSize(13);
        status.setTextColor(BLUE);
        status.setPadding(0, dp(10), 0, 0);
        return status;
    }

    private void setStatus(String text) {
        TextView status = findViewById(android.R.id.text1);
        if (status != null) status.setText(text);
    }

    private void finishOnboarding() {
        getSharedPreferences(ProviderConfig.PREFS, 0).edit().putBoolean(ProviderConfig.ONBOARDING_DONE, true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override protected void onPause() { if (pet != null) pet.pause(); super.onPause(); }

    @Override protected void onDestroy() {
        if (pet != null) pet.pause();
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
