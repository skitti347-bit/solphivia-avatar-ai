package it.solphivia.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * User-chosen model provider. Values:
 *  - "none":        nothing configured yet (onboarding pending)
 *  - "chatgpt":     Sign in with ChatGPT OAuth (legacy path, handled by OpenAiChatClient)
 *  - "openrouter":  https://openrouter.ai/api/v1 with a user key
 *  - "compatible":  any OpenAI-compatible base URL with a user key (vLLM, Groq, ...)
 *  - "local":       an LM Studio / Ollama server on the user's PC, key optional
 */
final class ProviderConfig {
    static final String PREFS = "provider";
    static final String ONBOARDING_DONE = "onboarding_done";

    private ProviderConfig() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }

    static String type(Context c) { return prefs(c).getString("type", "none"); }
    static String baseUrl(Context c) { return prefs(c).getString("base_url", ""); }
    static String model(Context c) { return prefs(c).getString("model", ""); }
    static String apiKey(Context c) { return SecretStore.read(c); }

    /** True when the independent-provider path should be used instead of ChatGPT. */
    static boolean isCustomActive(Context c) {
        String t = type(c);
        if ("openrouter".equals(t)) return !apiKey(c).isEmpty();
        return "compatible".equals(t) || "local".equals(t);
    }

    static String label(Context c) {
        switch (type(c)) {
            case "openrouter": return "OpenRouter";
            case "compatible": return "Endpoint compatibile";
            case "local": return "LLM locale";
            default: return "Provider";
        }
    }

    static boolean isClearText(Context c) { return baseUrl(c).trim().startsWith("http://"); }

    static void save(Context c, String type, String baseUrl, String model, String apiKey) {
        SharedPreferences.Editor edit = prefs(c).edit();
        edit.putString("type", type);
        edit.putString("base_url", baseUrl == null ? "" : baseUrl.trim());
        edit.putString("model", model == null ? "" : model.trim());
        edit.apply();
        SecretStore.write(c, apiKey == null ? "" : apiKey.trim());
    }

    /** POST URL for an OpenAI chat-completions call on the configured endpoint. */
    static String chatCompletionsUrl(Context c) {
        String t = type(c);
        if ("openrouter".equals(t)) return "https://openrouter.ai/api/v1/chat/completions";
        String base = baseUrl(c).trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/v1")) base = base + "/v1";
        return base + "/chat/completions";
    }

    /** GET {base}/models — supported by OpenRouter, LM Studio, Ollama and vLLM. */
    static String testConnection(Context c) {
        String url;
        if ("openrouter".equals(type(c))) url = "https://openrouter.ai/api/v1/models";
        else {
            String base = baseUrl(c).trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (!base.endsWith("/v1")) base = base + "/v1";
            url = base + "/models";
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            String key = apiKey(c);
            if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
            if ("openrouter".equals(type(c))) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/Trikto7e8/solphivia-avatar-ai");
                conn.setRequestProperty("X-Title", "Solphivia IA");
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = "";
                try (InputStream err = conn.getErrorStream()) {
                    if (err != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8));
                        StringBuilder b = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null && b.length() < 200) b.append(line);
                        body = b.toString();
                    }
                } finally { conn.disconnect(); }
                return "HTTP " + code + (body.isEmpty() ? "" : " · " + body);
            }
            conn.disconnect();
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? "connessione non riuscita" : e.getMessage();
        }
    }

    /** Models endpoint for an explicit (not yet saved) provider choice. */
    static String modelsUrlFor(String type, String baseUrl) {
        if ("openrouter".equals(type)) return "https://openrouter.ai/api/v1/models";
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/v1")) base = base + "/v1";
        return base + "/models";
    }

    /** Fetches the list of model ids from an OpenAI-compatible /models endpoint. Empty on any failure. */
    static java.util.ArrayList<String> fetchModels(String type, String baseUrl, String apiKey) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(modelsUrlFor(type, baseUrl)).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            if (apiKey != null && !apiKey.trim().isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            if ("openrouter".equals(type)) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/Trikto7e8/solphivia-avatar-ai");
                conn.setRequestProperty("X-Title", "Solphivia IA");
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return ids;
            org.json.JSONObject root = new org.json.JSONObject(readAll(conn.getInputStream()));
            org.json.JSONArray list = root.optJSONArray("data");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject m = list.optJSONObject(i);
                String id = m == null ? "" : m.optString("id", "");
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (Exception ignored) {
        } finally { if (conn != null) conn.disconnect(); }
        return ids;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[4096];
        int n;
        while ((n = reader.read(buffer)) != -1) out.append(buffer, 0, n);
        return out.toString();
    }
}
