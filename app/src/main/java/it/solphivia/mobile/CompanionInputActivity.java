package it.solphivia.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small relay activity for Android's speech recognizer and document picker. */
public class CompanionInputActivity extends Activity {
    static final String ACTION_VOICE = "it.solphivia.mobile.action.VOICE_INPUT";
    static final String ACTION_SETTINGS = "it.solphivia.mobile.action.SETTINGS";
    static final String ACTION_FILES = "it.solphivia.mobile.action.PICK_FILES";
    static final String ACTION_ATTACHMENTS = "it.solphivia.mobile.action.ATTACHMENTS";
    static final String ACTION_WEATHER_SETUP = "it.solphivia.mobile.action.WEATHER_SETUP";
    static final String EXTRA_URIS = "attachment_uris";
    private static final int REQUEST_VOICE = 31;
    private static final int REQUEST_FILES = 32;
    private static final int REQUEST_LOCATION = 33;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean locating;
    private volatile boolean destroyed;
    private boolean externalRequestPending;

    private boolean active() { return !destroyed && !isFinishing() && !isDestroyed(); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        externalRequestPending = state != null && state.getBoolean("external_request_pending", false);
        if (externalRequestPending) return;
        String action = getIntent() == null ? null : getIntent().getAction();
        if (ACTION_SETTINGS.equals(action)) {
            showCyberMenu();
        }
        else if (ACTION_WEATHER_SETUP.equals(action)) chooseWeatherLocation();
        else if (ACTION_VOICE.equals(action)) openVoiceInput();
        else openFilePicker();
    }

    /** HyperOS blocks plain startActivity from a background-attached dialog; the sanctioned
     *  path on Android 14+ is a PendingIntent whose CREATOR background-start mode is allowed. */
    private void launchFromBackground(Class<?> cls, String extraKey) {
        Intent intent = new Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (extraKey != null) intent.putExtra(extraKey, true);
        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
        if (android.os.Build.VERSION.SDK_INT >= 34)
            options.setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        try {
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(this, cls.getName().hashCode(), intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT, options.toBundle());
            pending.send(this, 0, null, new android.app.PendingIntent.OnFinished() {
                @Override public void onSendFinished(android.app.PendingIntent p, Intent sent, int code, String resultData, Bundle resultExtras) { finish(); }
            }, null);
        }
        catch (android.app.PendingIntent.CanceledException | RuntimeException error) {
            try { startActivity(intent); } catch (RuntimeException ignored) { }
            finish();
        }
    }

    private void showCyberMenu() {
        boolean overlayUp = PetService.overlayVisible();
        boolean voiceOn = PetService.voiceOutputEnabled(this);
        final String[] items = {"Memoria e Memory Vault", "Prova animazioni",
                overlayUp ? "Apri Chat" : "Live Avatar",
                voiceOn ? "Silenzia la voce" : "Riattiva la voce",
                "Ripeti l'ultima frase", "Cambia modello"};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF0A0E1C);
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Solphivia");
        title.setTextSize(20); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFEAF2FF);
        title.setShadowLayer(pad, 0, 0, 0xFF00E5FF);
        box.addView(title);
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            TextView row = new TextView(this);
            row.setText(items[i]);
            row.setTextSize(15);
            row.setTextColor(0xFFEAF2FF);
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(0xFF131A2E);
            background.setCornerRadius(pad);
            background.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), 0xFF2A3554);
            row.setBackground(background);
            row.setPadding(pad, pad / 2, pad, pad / 2);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, pad / 2, 0, 0);
            row.setLayoutParams(params);
            row.setOnClickListener(v -> {
                if (which == 5) { launchFromBackground(OnboardingActivity.class, "change_model"); return; }
                if (which == 4) {
                    if (!PetService.repeatLastSpeech(this))
                        Toast.makeText(this, "Non c'è una frase recente da ripetere.", Toast.LENGTH_SHORT).show();
                    finish(); return;
                }
                if (which == 3) {
                    boolean on = PetService.voiceOutputEnabled(this);
                    PetService.setVoiceOutputEnabled(this, !on);
                    Toast.makeText(this, on ? "Voce silenziata: ora risponde solo per iscritto." : "Voce di Edge riattivata.", Toast.LENGTH_SHORT).show();
                    finish(); return;
                }
                if (which == 2) {
                    if (overlayUp) {
                        launchFromBackground(MainActivity.class, "stay_open");
                    } else {
                        getSharedPreferences("pet", 0).edit().putBoolean("overlay_off", false).apply();
                        if (android.os.Build.VERSION.SDK_INT >= 26)
                            startForegroundService(new Intent(this, PetService.class).setAction(PetService.ACTION_GREET));
                        else startService(new Intent(this, PetService.class).setAction(PetService.ACTION_GREET));
                    }
                    finish(); return;
                }
                startActivity(new Intent(this, which == 0 ? MemoryActivity.class : AnimationPreviewActivity.class));
                finish();
            });
            box.addView(row);
        }
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(box);
        dialog.setOnCancelListener(d -> finish());
        dialog.show();
    }

    private void chooseWeatherLocation() {
        new CyberDialogs.Builder(this, "Solphivia Meteorina",
                "Come vuoi indicare la posizione per il meteo? Le coordinate saranno inviate a Open-Meteo per ottenere le previsioni e conservate sul telefono per gli aggiornamenti.")
                .button("Usa GPS", 0, d -> { d.dismiss(); useGps(); })
                .button("Scrivo una località", 1, d -> { d.dismiss(); askCity(); })
                .button("Annulla", 1, Dialog::dismiss)
                .cancelFinishes().show();
    }

    private void useGps() {
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"}, REQUEST_LOCATION);
            return;
        }
        findLocation();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_LOCATION) return;
        boolean granted = false;
        for (int result : grants) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
        if (granted) findLocation();
        else new CyberDialogs.Builder(this, "Posizione non autorizzata",
                "Puoi comunque scegliere una città senza attivare il GPS.")
                .button("Scegli città", 0, d -> { d.dismiss(); askCity(); })
                .button("Annulla", 1, Dialog::dismiss)
                .cancelFinishes().show();
    }

    private void findLocation() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String provider : locationManager.getProviders(true)) {
                try {
                    Location candidate = locationManager.getLastKnownLocation(provider);
                    if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
                } catch (SecurityException ignored) { }
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 2 * 60 * 60 * 1000L) {
                sendWeatherLocation(best);
                return;
            }
            locating = true;
            Toast.makeText(this, "Cerco la posizione GPS…", Toast.LENGTH_SHORT).show();
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (!locating) return;
                    locating = false; main.removeCallbacks(locationTimeout); sendWeatherLocation(location);
                }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
            };
            boolean requested = false;
            for (String provider : locationManager.getProviders(true)) {
                try { locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper()); requested = true; }
                catch (SecurityException ignored) { }
            }
            if (!requested) { locating = false; askCity(); return; }
            main.postDelayed(locationTimeout, 20000L);
        } catch (SecurityException | IllegalArgumentException error) {
            askCity();
        }
    }

    private final Runnable locationTimeout = () -> {
        if (!locating) return;
        locating = false;
        removeLocationUpdates();
        new CyberDialogs.Builder(this, "Posizione non disponibile",
                "Non ho ricevuto una posizione GPS. Puoi inserire la città a mano.")
                .button("Scegli città", 0, d -> { d.dismiss(); askCity(); })
                .button("Annulla", 1, Dialog::dismiss)
                .cancelFinishes().show();
    };

    private void sendWeatherLocation(Location location) {
        removeLocationUpdates();
        Intent update = new Intent(this, PetService.class).setAction(PetService.ACTION_WEATHER_LOCATION)
                .putExtra(PetService.EXTRA_LATITUDE, location.getLatitude())
                .putExtra(PetService.EXTRA_LONGITUDE, location.getLongitude())
                .putExtra(PetService.EXTRA_PLACE, "Posizione GPS");
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(update); else startService(update);
        Toast.makeText(this, "Meteorina aggiornata dalla posizione GPS", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void removeLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) { }
        }
        locationListener = null;
    }

    private void askCity() {
        if (!active()) return;
        String saved = getSharedPreferences("weather", 0).getString("place", "Torre a Mare, Bari");
        EditText input = CyberDialogs.styledInput(this, "es. Torre a Mare, Bari", saved);
        new CyberDialogs.Builder(this, "Scegli la località",
                "Inserisci una città o una località. Non userò il GPS; le coordinate della località saranno inviate a Open-Meteo per le previsioni.")
                .customView(input)
                .button("Usa questa località", 0, d -> { d.dismiss(); geocode(input.getText().toString().trim()); })
                .button("Annulla", 1, Dialog::dismiss)
                .cancelFinishes().show();
    }

    private void geocode(String query) {
        if (query.isEmpty()) { finish(); return; }
        Toast.makeText(this, "Cerco la località…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String address = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(query, "UTF-8") + "&count=1&language=it&format=json";
                HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) body.append(line);
                } finally { connection.disconnect(); }
                JSONArray results = new JSONObject(body.toString()).optJSONArray("results");
                if (results == null || results.length() == 0) throw new IllegalArgumentException("Località non trovata.");
                JSONObject item = results.getJSONObject(0);
                String label = item.optString("name", query);
                String admin = item.optString("admin1", "");
                String country = item.optString("country", "");
                if (!admin.isEmpty() && !admin.equals(label)) label += ", " + admin;
                if (!country.isEmpty()) label += ", " + country;
                final String locationLabel = label;
                Intent update = new Intent(this, PetService.class).setAction(PetService.ACTION_WEATHER_LOCATION)
                        .putExtra(PetService.EXTRA_LATITUDE, item.getDouble("latitude"))
                        .putExtra(PetService.EXTRA_LONGITUDE, item.getDouble("longitude"))
                        .putExtra(PetService.EXTRA_PLACE, locationLabel);
                main.post(() -> {
                    if (!active()) return;
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(update); else startService(update);
                    Toast.makeText(this, "Località meteo impostata: " + locationLabel, Toast.LENGTH_SHORT).show(); finish();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!active()) return;
                    Toast.makeText(this, error.getMessage() == null ? "Ricerca della località non riuscita." : error.getMessage(), Toast.LENGTH_LONG).show();
                    askCity();
                });
            }
        }, "solphivia-geocode").start();
    }

    private void openVoiceInput() {
        Intent voice = VoicePhrases.recognitionIntent();
        voice.putExtra(RecognizerIntent.EXTRA_PROMPT, "Parla con Solphivia");
        try { externalRequestPending = true; startActivityForResult(voice, REQUEST_VOICE); }
        catch (Exception e) {
            Toast.makeText(this, "Riconoscimento vocale non disponibile sul telefono.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void openFilePicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "image/*", "text/plain", "text/markdown", "text/csv"
        });
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { externalRequestPending = true; startActivityForResult(pick, REQUEST_FILES); }
        catch (Exception e) {
            Toast.makeText(this, "Non trovo un selettore di file sul telefono.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        externalRequestPending = false;
        if (!active()) return;
        if(requestCode==REQUEST_VOICE && !getIntent().getBooleanExtra("inline",false))
            startForegroundService(new Intent(this,PetService.class).setAction(PetService.ACTION_INPUT_DONE));
        if (resultCode != RESULT_OK || data == null) { finish(); return; }
        if (requestCode == REQUEST_VOICE) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty() && !matches.get(0).trim().isEmpty()) {
                String recognized=VoicePhrases.best(matches);
                if(getIntent().getBooleanExtra("inline",false)) { setResult(RESULT_OK,new Intent().putExtra("dictation",recognized)); }
                else { Intent send = new Intent(this, PetService.class).putExtra(PetService.EXTRA_DRAFT_MESSAGE, recognized); startForegroundService(send); }
            }
        } else if (requestCode == REQUEST_FILES) {
            ArrayList<Uri> selected = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount() && i < 5; i++)
                    selected.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) selected.add(data.getData());
            ArrayList<String> values = new ArrayList<>();
            for (Uri uri : selected) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { }
                values.add(uri.toString());
            }
            if (!values.isEmpty()) {
                if(getIntent().getBooleanExtra("inline",false)) {
                    Intent result=new Intent().putStringArrayListExtra(EXTRA_URIS,values).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if(data.getClipData()!=null)result.setClipData(data.getClipData());
                    else result.setClipData(ClipData.newRawUri("allegato",selected.get(0)));
                    setResult(RESULT_OK,result); finish();return;
                }
                Intent send = new Intent(this, PetService.class).setAction(ACTION_ATTACHMENTS)
                        .putStringArrayListExtra(EXTRA_URIS, values)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (data.getClipData() != null) send.setClipData(data.getClipData());
                else if (!selected.isEmpty()) send.setClipData(ClipData.newRawUri("allegato", selected.get(0)));
                startForegroundService(send);
            }
        }
        finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        locating = false;
        main.removeCallbacks(locationTimeout);
        removeLocationUpdates();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("external_request_pending", externalRequestPending);
        super.onSaveInstanceState(state);
    }
}
