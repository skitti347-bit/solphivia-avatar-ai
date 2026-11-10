package it.solphivia.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import it.solphivia.mobile.provider.ProviderResponse;
import it.solphivia.mobile.provider.SolphiviaResponseParser;

/**
 * Streaming chat-completions client for the independent providers chosen during
 * onboarding: OpenRouter, any OpenAI-compatible endpoint, or a local LM Studio /
 * Ollama server on the user's PC. Same personality prompt and same response-tag
 * parser as the ChatGPT path, so the avatar behaviour is identical.
 */
final class CustomProviderClient {
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 180000;

    private CustomProviderClient() {}

    /** Streams a reply for the given chat history using the configured provider. */
    static void respond(Context context, ExecutorService worker, JSONArray history, boolean voice,
                        OpenAiChatClient.ChatCallback callback) {
        try {
            worker.execute(() -> {
                try {
                    String raw = call(context, history, voice);
                    ProviderResponse parsed = SolphiviaResponseParser.parse(raw);
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> callback.onSuccess(parsed.getText(), parsed.getAnimation(),
                            parsed.getMotion(), parsed.getMode()));
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
                    String label = ProviderConfig.label(context);
                    String full = label + ": " + message;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onError(full));
                }
            });
        } catch (RejectedExecutionException ignored) {
            callback.onError(ProviderConfig.label(context) + ": il servizio è stato fermato.");
        }
    }

    private static String call(Context context, JSONArray history, boolean voice) throws Exception {
        String memory = context.getSharedPreferences("memory", 0).getBoolean("enabled", true)
                ? AssistantMemory.read(context) : "";
        String url = ProviderConfig.chatCompletionsUrl(context);
        String model = ProviderConfig.model(context);
        if (model.isEmpty()) throw new IllegalStateException("scegli un modello nell'onboarding.");

        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("stream", true);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", OpenAiChatClient.instructionsFor(memory));
        messages.put(system);
        int start = Math.max(0, history.length() - 16);
        for (int i = start; i < history.length(); i++) {
            JSONObject line = history.getJSONObject(i);
            JSONObject message = new JSONObject();
            message.put("role", line.optString("role", "user"));
            message.put("content", line.optString("content", ""));
            messages.put(message);
        }
        request.put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoOutput(true);
        String key = ProviderConfig.apiKey(context);
        if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
        if ("openrouter".equals(ProviderConfig.type(context))) {
            conn.setRequestProperty("HTTP-Referer", "https://github.com/Trikto7e8/solphivia-avatar-ai");
            conn.setRequestProperty("X-Title", "Solphivia IA");
        }
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String body = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && builder.length() < 300) builder.append(line);
                body = builder.toString();
            } catch (Exception ignored) {
            } finally { conn.disconnect(); }
            throw new IllegalStateException("HTTP " + code + (body.isEmpty() ? "" : " · " + body));
        }

        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                JSONObject event;
                try {
                    event = new JSONObject(data);
                } catch (Exception ignored) {
                    continue;
                }
                JSONArray choices = event.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) continue;
                JSONObject delta = choice.optJSONObject("delta");
                if (delta != null) text.append(delta.optString("content", ""));
            }
        } finally { conn.disconnect(); }
        String answer = text.toString().trim();
        if (answer.isEmpty()) throw new IllegalStateException("il modello ha restituito una risposta vuota.");
        return answer;
    }
}
