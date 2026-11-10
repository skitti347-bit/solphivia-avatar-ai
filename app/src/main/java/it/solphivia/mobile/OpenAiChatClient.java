package it.solphivia.mobile;

import it.solphivia.mobile.provider.FrozenChatRequest;
import it.solphivia.mobile.provider.GenerationListener;
import it.solphivia.mobile.provider.OpenAiCompatibilityProvider;
import it.solphivia.mobile.provider.OpenAiStreamListener;
import it.solphivia.mobile.provider.ProviderConfiguration;
import it.solphivia.mobile.provider.ProviderConfigurations;
import it.solphivia.mobile.provider.ProviderError;
import it.solphivia.mobile.provider.ProviderRequestFactory;
import it.solphivia.mobile.provider.ProviderResponse;
import it.solphivia.mobile.provider.RequestCancellation;
import it.solphivia.mobile.provider.RequestLifecycle;
import it.solphivia.mobile.provider.RequestMode;
import it.solphivia.mobile.provider.SingleUseMemoryConsent;
import it.solphivia.mobile.provider.MemoryConsentResult;
import it.solphivia.mobile.provider.AttachmentSnapshot;
import it.solphivia.mobile.provider.ConversationMessage;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Locale;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.math.BigInteger;
import java.lang.ref.WeakReference;

/** OpenAI Sign in with ChatGPT and Responses API transport. Tokens stay in app-private encrypted storage. */
final class OpenAiChatClient {
    private static final String AUTHORIZE = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String ISSUER = "https://auth.openai.com";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String PREFS = "chatgpt_oauth";
    private static final String KEY_ALIAS = "solphivia-chatgpt-tokens-v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean authorizationRunning;
    private static volatile WeakReference<Activity> authorizationOwner;
    private static volatile ServerSocket authorizationSocket;
    private static final Map<String, RequestCancellation> ACTIVE_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, MemoryConsentActivity.RequestHandle> PENDING_CONSENTS = new ConcurrentHashMap<>();
    private static final Map<String, ExecutorService> REQUEST_OWNERS = new ConcurrentHashMap<>();
    private static final SingleUseMemoryConsent MEMORY_CONSENTS = new SingleUseMemoryConsent();
    private static final Object REQUEST_LOCK = new Object();
    private static final GenerationListener NO_OP_LISTENER = new GenerationListener() {
        @Override public void onDelta(String text) { }
        @Override public void onCompleted(ProviderResponse response) { }
        @Override public void onError(ProviderError error) { }
    };

    static void cancelAuthorization(Activity activity) {
        Activity owner = authorizationOwner == null ? null : authorizationOwner.get();
        if (owner != activity) return;
        ServerSocket socket = authorizationSocket;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }

    private static boolean alive(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static final class AuthenticationRequired extends IllegalStateException {
        AuthenticationRequired() { super("Accesso scaduto o revocato. Ricollega ChatGPT dall’app."); }
    }

    private static void requireAuthentication(Context context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("plan_allowed", false)
                .remove("model").apply();
    }

    interface AuthCallback { void onSuccess(String account); void onError(String message); }
    interface ChatCallback { void onSuccess(String reply, String animation, String motion, String mode); void onError(String message); }

    private OpenAiChatClient() {}

    static void cancelRequests(ExecutorService worker) {
        synchronized (REQUEST_LOCK) {
            for (Map.Entry<String, ExecutorService> entry : REQUEST_OWNERS.entrySet()) {
                if (entry.getValue() == worker) cancelRequest(entry.getKey());
            }
        }
    }

    private static void cancelRequest(String requestId) {
        MemoryConsentActivity.RequestHandle consent = PENDING_CONSENTS.remove(requestId);
        if (consent != null) consent.cancel();
        RequestCancellation request = ACTIVE_REQUESTS.remove(requestId);
        if (request != null) request.cancel();
        REQUEST_OWNERS.remove(requestId);
    }

    private static void finishRequest(String requestId) {
        ACTIVE_REQUESTS.remove(requestId);
        PENDING_CONSENTS.remove(requestId);
        REQUEST_OWNERS.remove(requestId);
    }

    static boolean isConnected(Context context) {
        return !secret(context,"access_token").isEmpty() &&
                context.getSharedPreferences(PREFS,0).getBoolean("plan_allowed",false);
    }

    static void authorize(Activity activity, ExecutorService worker, AuthCallback callback) {
        synchronized (OpenAiChatClient.class) {
            if (authorizationRunning) {
                postAuthError(callback, "Autorizzazione ChatGPT già in corso. Completa quella aperta e torna a Solphivia.");
                return;
            }
            authorizationRunning = true;
            authorizationOwner = new WeakReference<>(activity);
        }
        Intent keepAlive = new Intent(activity, OAuthKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(keepAlive);
            else activity.startService(keepAlive);
            worker.execute(() -> {
                String account = null;
                String error = null;
                try { account = doAuthorize(activity); }
                catch (Exception e) { error = "Accesso ChatGPT non riuscito: " + safeMessage(e); }
                final String result = account, failure = error;
                MAIN.post(() -> {
                    activity.stopService(keepAlive);
                    synchronized (OpenAiChatClient.class) {
                        authorizationOwner = null;
                        authorizationSocket = null;
                        authorizationRunning = false;
                    }
                    if (!alive(activity)) return;
                    if (failure == null) callback.onSuccess(result); else callback.onError(failure);
                });
            });
        } catch (Exception e) {
            authorizationOwner = null;
            authorizationRunning = false;
            activity.stopService(keepAlive);
            postAuthError(callback, "Non riesco a mantenere attivo il collegamento ChatGPT: " + safeMessage(e));
        }
    }

    static boolean isAuthorizing() { return authorizationRunning; }

    private static String doAuthorize(Activity activity) throws Exception {
        Context context=activity.getApplicationContext();
        String host=context.getSharedPreferences(PREFS,0).getString("host_id",null);
        if(host==null){host="urn:uuid:"+UUID.randomUUID();context.getSharedPreferences(PREFS,0).edit().putString("host_id",host).apply();}
        String state=randomUrlSafe(32), nonce=randomUrlSafe(32), verifier=randomUrlSafe(48);
        String challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)),Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
        ServerSocket server=new ServerSocket();
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),1);
        // The browser may take several minutes; OAuthKeepAliveService keeps this
        // local loopback listener alive while Android backgrounds the activity.
        server.setSoTimeout(900000);
        authorizationSocket = server;
        if (!alive(activity)) { server.close(); throw new IllegalStateException("Collegamento annullato."); }
        String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
        String savedClient=context.getSharedPreferences(PREFS,0).getString("client_id","");
        boolean firstRegistration=savedClient.isEmpty();
        String clientId=firstRegistration?"dynamic_agent_client":savedClient;
        Uri.Builder builder=Uri.parse(AUTHORIZE).buildUpon()
                .appendQueryParameter("client_id",clientId)
                .appendQueryParameter("ext_agent_host_id",host)
                .appendQueryParameter("response_type","code")
                .appendQueryParameter("redirect_uri",redirect)
                .appendQueryParameter("scope",SCOPES)
                .appendQueryParameter("resource",RESOURCE)
                .appendQueryParameter("state",state)
                .appendQueryParameter("nonce",nonce)
                .appendQueryParameter("code_challenge_method","S256")
                .appendQueryParameter("code_challenge",challenge);
        if(firstRegistration) builder.appendQueryParameter("agent_name_hint","Solphivia IA");
        else {
            String hint=secret(context,"id_token");
            if(!hint.isEmpty()) builder.appendQueryParameter("id_token_hint",hint);
            String email=context.getSharedPreferences(PREFS,0).getString("email","");
            if(!email.isEmpty()) builder.appendQueryParameter("login_hint",email);
        }
        Uri authUri=builder.build();
        MAIN.post(() -> {
            if (!alive(activity)) { cancelAuthorization(activity); return; }
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW,authUri)); }
            catch(Exception e) { cancelAuthorization(activity); }
        });

        Callback callbackData;
        try(Socket socket=server.accept()) { callbackData=readCallback(socket); }
        finally { try{server.close();}catch(Exception ignored){} }
        if(!state.equals(callbackData.params.get("state"))) throw new SecurityException("Il controllo di sicurezza OAuth non è riuscito.");
        if(callbackData.params.containsKey("error")) throw new IllegalStateException("Autorizzazione annullata o negata da ChatGPT.");
        String code=callbackData.params.get("code");
        String callbackClient=callbackData.params.get("client_id");
        String issuedClient=callbackClient==null?savedClient:callbackClient;
        if(!firstRegistration&&!savedClient.equals(issuedClient)) throw new SecurityException("ChatGPT ha restituito un account diverso da quello selezionato.");
        if(code==null||issuedClient==null||issuedClient.isEmpty()||issuedClient.equals("dynamic_agent_client"))
            throw new IllegalStateException("ChatGPT non ha completato la registrazione dell’app.");

        JSONObject tokens=postForm(TOKEN,form("grant_type","authorization_code","client_id",issuedClient,
                "code",code,"code_verifier",verifier,"redirect_uri",redirect,"resource",RESOURCE));
        String idToken=tokens.optString("id_token","");
        if(idToken.isEmpty()) throw new SecurityException("ChatGPT non ha restituito l’identità dell’account.");
        JSONObject claims=validateIdToken(idToken,issuedClient,nonce);
        String granted=tokens.optString("scope",callbackData.params.getOrDefault("scope",""));
        boolean planAllowed=containsScope(granted,"chatgpt.tokens.use.direct") && containsScope(granted,"resource.invoke");
        if(!planAllowed) throw new SecurityException("Il permesso per le richieste IA non è stato concesso. Ricollega ChatGPT e approva l’uso del piano.");

        JSONObject profile=claims.optJSONObject("https://api.openai.com/auth");
        String email=claims.optString("email", profile==null?"":profile.optString("email",""));
        long expires=System.currentTimeMillis()+Math.max(60,tokens.optLong("expires_in",3600))*1000L;
        android.content.SharedPreferences p=context.getSharedPreferences(PREFS,0);
        if (!alive(activity)) throw new IllegalStateException("Collegamento annullato.");
        p.edit().putString("client_id",issuedClient).putString("email",email)
                .putString("scope",granted).putLong("expires_at",expires).putBoolean("plan_allowed",true).remove("model").apply();
        putSecret(context,"access_token",tokens.optString("access_token",""));
        putSecret(context,"refresh_token",tokens.optString("refresh_token",""));
        putSecret(context,"id_token",idToken);
        return email.isEmpty()?"ChatGPT":email;
    }

    static void respond(Context context, ExecutorService worker, JSONArray history, ChatCallback callback) {
        respond(context, worker, history, new JSONArray(), callback);
    }

    static void respond(Context context, ExecutorService worker, JSONArray history,
                        JSONArray attachments, ChatCallback callback) {
        respond(context,worker,history,attachments,false,callback);
    }

    static JSONArray requestHistory(JSONArray history, boolean voice) throws Exception {
        JSONArray copy=new JSONArray(history.toString());
        if(voice)for(int i=0;i<copy.length();i++){JSONObject line=copy.optJSONObject(i);if(line!=null)line.remove("attachments");}
        return copy;
    }

    static void respond(Context context, ExecutorService worker, JSONArray history,
                        JSONArray attachments, boolean voice, ChatCallback callback) {
        if (worker.isShutdown()) return;
        String requestId = UUID.randomUUID().toString();
        REQUEST_OWNERS.put(requestId, worker);
        ACTIVE_REQUESTS.put(requestId, new RequestLifecycle(NO_OP_LISTENER));
        try {
            JSONArray frozenHistory = requestHistory(history,voice);
            if(voice){
                // Speaking is an explicit send. Personal vault memory and files still require their separate review.
                respondApproved(context,worker,requestId,frozenHistory,new JSONArray(),"",true,callback);return;
            }
            JSONArray frozenAttachments = new JSONArray(attachments.toString());
            String memory = context.getSharedPreferences("memory", 0).getBoolean("enabled", true) ? AssistantMemory.read(context) : "";
            StringBuilder preview = new StringBuilder("Conversazione inviata:\n");
            for (int i=0; i<frozenHistory.length(); i++) {
                JSONObject line = frozenHistory.getJSONObject(i);
                preview.append(line.optString("role")).append(": ").append(line.optString("content")).append("\n\n");
                JSONArray refs = line.optJSONArray("attachments");
                if (refs != null) for (int j=0; j<refs.length(); j++) preview.append("Allegato: ").append(refs.getJSONObject(j).optString("filename")).append('\n');
            }
            RequestCancellation preflight = ACTIVE_REQUESTS.get(requestId);
            Future<?> preflightFuture = worker.submit(() -> {
                try {
                    ProviderConfiguration configuration = resolveConfiguration(context);
                    JSONArray materialized = materializeForProvider(context, frozenHistory, frozenAttachments);
                    if (preflight != null && preflight.isCancelled()) {
                        finishRequest(requestId);
                        return;
                    }
                    java.util.List<it.solphivia.mobile.provider.AttachmentSnapshot> snapshots =
                            ProviderRequestFactory.materializedAttachments(materialized);
                    it.solphivia.mobile.provider.MemoryConsentGrant grant =
                            MEMORY_CONSENTS.issue(configuration, snapshots, memory, 300_000L);
                    MAIN.post(() -> {
                        if (worker.isShutdown() || (preflight != null && preflight.isCancelled())) {
                            MEMORY_CONSENTS.cancel(grant.getId());
                            finishRequest(requestId);
                            return;
                        }
                        MemoryConsentActivity.RequestHandle consent = MemoryConsentActivity.request(
                                context, preview.toString(), memory, "OpenAI / ChatGPT",
                                approved -> {
                                    PENDING_CONSENTS.remove(requestId);
                                    try {
                                        MemoryConsentResult result = MEMORY_CONSENTS.consume(
                                                grant.getId(), configuration, snapshots, approved);
                                        if (result instanceof MemoryConsentResult.Approved) {
                                            dispatchFrozen(context, worker, requestId, materialized,
                                                    ((MemoryConsentResult.Approved) result).getText(), false,
                                                    configuration, grant.getId(), callback);
                                        } else {
                                            finishRequest(requestId);
                                            callback.onError("Memoria: consenso non valido. Invia di nuovo il messaggio.");
                                        }
                                    } catch (Exception error) {
                                        finishRequest(requestId);
                                        callback.onError("Memoria: consenso non valido. Invia di nuovo il messaggio.");
                                    }
                                },
                                () -> {
                                    MEMORY_CONSENTS.cancel(grant.getId());
                                    finishRequest(requestId);
                                    callback.onError("Invio annullato: nessuna richiesta è stata inviata.");
                                });
                        PENDING_CONSENTS.put(requestId, consent);
                    });
                } catch (Exception e) {
                    boolean cancelled = preflight != null && preflight.isCancelled();
                    finishRequest(requestId);
                    if (!cancelled) MAIN.post(() -> callback.onError("Memoria: " + safeMessage(e)));
                }
            });
            if (preflight != null) preflight.onCancel(() -> { preflightFuture.cancel(true); return kotlin.Unit.INSTANCE; });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            finishRequest(requestId);
            MAIN.post(() -> callback.onError("Memoria: il servizio è stato fermato."));
        } catch (Exception e) {
            finishRequest(requestId);
            MAIN.post(() -> callback.onError("Memoria: " + safeMessage(e)));
        }
    }

    private static void respondApproved(Context context, ExecutorService worker, String requestId, JSONArray history,
                                        JSONArray attachments, String memory, boolean voice, ChatCallback callback) {
        if (worker.isShutdown()) { finishRequest(requestId); return; }
        try {
            RequestCancellation preflight = ACTIVE_REQUESTS.get(requestId);
            Future<?> preflightFuture = worker.submit(() -> {
                try {
                    ProviderConfiguration configuration = resolveConfiguration(context);
                    JSONArray materialized = materializeForProvider(context, history, attachments);
                    if (preflight != null && preflight.isCancelled()) {
                        finishRequest(requestId);
                        return;
                    }
                    MAIN.post(() -> dispatchFrozen(context, worker, requestId, materialized, memory, voice, configuration, null, callback));
                } catch (Exception e) {
                    boolean cancelled = preflight != null && preflight.isCancelled();
                    finishRequest(requestId);
                    if (!cancelled) MAIN.post(() -> callback.onError("ChatGPT: " + safeMessage(e)));
                }
            });
            if (preflight != null) preflight.onCancel(() -> { preflightFuture.cancel(true); return kotlin.Unit.INSTANCE; });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            finishRequest(requestId);
            MAIN.post(() -> callback.onError("ChatGPT: il servizio è stato fermato."));
        }
    }

    private static ProviderConfiguration resolveConfiguration(Context context) throws Exception {
        refreshIfNeeded(context);
        String access = secret(context, "access_token");
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        String model = prefs.getString("model", "");
        if (model.isEmpty()) {
            model = chooseModel(context, access);
            prefs.edit().putString("model", model).apply();
        }
        return ProviderConfigurations.chatGpt(model);
    }

    private static void dispatchFrozen(Context context, ExecutorService worker, String requestId,
                                       JSONArray materialized, String memory, boolean voice,
                                       ProviderConfiguration configuration, String memoryConsentId,
                                       ChatCallback callback) {
        if (worker.isShutdown()) { finishRequest(requestId); return; }
        try {
            FrozenChatRequest request = ProviderRequestFactory.freezeLegacy(
                    materialized, new JSONArray(), voice ? RequestMode.VOICE : RequestMode.TYPED,
                    configuration, memory, System.currentTimeMillis(), memoryConsentId);
            OpenAiCompatibilityProvider provider = new OpenAiCompatibilityProvider(
                    worker,
                    (frozen, stream, cancellation) -> {
                        // Dispatch only the immutable, consent-bound request. Do not reread the
                        // URI-backed history after approval or use the mutable JSON preflight copy.
                        String answer = callResponses(context, frozen, cancellation, stream);
                        stream.onCompleted(answer);
                    });
            synchronized (REQUEST_LOCK) {
                RequestCancellation previous = ACTIVE_REQUESTS.get(requestId);
                if (previous != null && previous.isCancelled()) {
                    finishRequest(requestId);
                    return;
                }
                RequestCancellation handle = provider.generate(request, new GenerationListener() {
                    @Override public void onDelta(String text) { /* Legacy ChatCallback is terminal-only. */ }

                    @Override public void onCompleted(ProviderResponse response) {
                        finishRequest(requestId);
                        MAIN.post(() -> callback.onSuccess(response.getText(), response.getAnimation(),
                                response.getMotion(), response.getMode()));
                    }

                    @Override public void onError(ProviderError error) {
                        finishRequest(requestId);
                        MAIN.post(() -> callback.onError("ChatGPT: " + error.getMessage()));
                    }
                });
                if (!handle.isTerminated()) ACTIVE_REQUESTS.put(requestId, handle);
            }
        } catch (Exception e) {
            finishRequest(requestId);
            MAIN.post(() -> callback.onError("ChatGPT: " + safeMessage(e)));
        }
    }

    private static JSONArray materializeForProvider(Context context, JSONArray history, JSONArray attachments) throws Exception {
        JSONArray original = new JSONArray(history.toString());
        JSONArray copy = new JSONArray();
        int start = Math.max(0, original.length() - 16);
        for (int i = start; i < original.length(); i++) copy.put(original.get(i));
        long[] total = {0L};
        for (int i = 0; i < copy.length(); i++) {
            JSONObject line = copy.optJSONObject(i);
            if (line == null || !"user".equals(line.optString("role"))) continue;
            JSONArray refs;
            if (i == copy.length() - 1 && attachments.length() > 0) {
                refs = new JSONArray(attachments.toString());
            } else {
                refs = line.optJSONArray("attachments");
            }
            if (refs == null || refs.length() == 0) continue;
            ArrayList<String> uris = new ArrayList<>();
            boolean alreadyMaterialized = true;
            for (int j = 0; j < refs.length(); j++) {
                JSONObject item = refs.optJSONObject(j);
                if (item == null) throw new IllegalArgumentException("Allegato storico non valido.");
                if (item.optString("data_uri", "").isEmpty()) {
                    alreadyMaterialized = false;
                }
                uris.add(item.getString("uri"));
            }
            if (!alreadyMaterialized) refs = AttachmentReader.read(context, uris, total);
            line.put("attachments", refs);
        }
        return copy;
    }

    static JSONArray buildInput(Context context, JSONArray history, JSONArray attachments) throws Exception {
        JSONArray input=new JSONArray();
        long[] attachmentBytes = {0};
        int start=Math.max(0,history.length()-16);
        for(int i=start;i<history.length();i++) {
            JSONObject old=history.optJSONObject(i); if(old==null) continue;
            String role=old.optString("role"); if(!role.equals("user")&&!role.equals("assistant")) continue;
            JSONArray messageAttachments = new JSONArray();
            if (role.equals("user")) {
                if (i == history.length() - 1 && attachments.length() > 0) {
                    messageAttachments = attachments;
                    for (int j = 0; j < attachments.length(); j++) attachmentBytes[0] += attachments.getJSONObject(j).optLong("size", 0);
                    if (attachmentBytes[0] > AttachmentReader.MAX_TOTAL) throw new IllegalArgumentException("Allegati della conversazione oltre 15 MB. Inizia una nuova chat.");
                } else {
                    JSONArray refs = old.optJSONArray("attachments");
                    if (refs != null) {
                        boolean materialized = true;
                        java.util.ArrayList<String> uris = new java.util.ArrayList<>();
                        for (int j = 0; j < refs.length(); j++) {
                            JSONObject ref = refs.getJSONObject(j);
                            if (ref.optString("data_uri", "").isEmpty()) {
                                materialized = false;
                            }
                            uris.add(ref.getString("uri"));
                        }
                        if (materialized) {
                            messageAttachments = refs;
                            for (int j = 0; j < refs.length(); j++) {
                                attachmentBytes[0] += refs.getJSONObject(j).optLong("size", 0);
                                if (attachmentBytes[0] > AttachmentReader.MAX_TOTAL) throw new IllegalArgumentException("Allegati della conversazione oltre 15 MB. Inizia una nuova chat.");
                            }
                        } else {
                            messageAttachments = AttachmentReader.read(context, uris, attachmentBytes);
                        }
                    }
                }
            }
            if(role.equals("user") && messageAttachments.length()>0) {
                JSONArray content=new JSONArray().put(new JSONObject()
                        .put("type","input_text").put("text",old.optString("content")));
                for(int j=0;j<messageAttachments.length();j++) {
                    JSONObject file=messageAttachments.optJSONObject(j); if(file==null) continue;
                    String mime=file.optString("mime_type","application/octet-stream");
                    String data=file.optString("data_uri","");
                    String filename=file.optString("filename","allegato");
                    if(data.isEmpty()) continue;
                    if(mime.startsWith("image/")) {
                        content.put(new JSONObject().put("type","input_image").put("detail","auto").put("image_url",data));
                    } else {
                        content.put(new JSONObject().put("type","input_file").put("filename",filename).put("file_data",data));
                    }
                }
                input.put(new JSONObject().put("role",role).put("content",content));
            } else input.put(new JSONObject().put("role",role).put("content",old.optString("content")));
        }
        return input;
    }

    private static JSONArray buildInput(FrozenChatRequest request) throws Exception {
        JSONArray input = new JSONArray();
        for (ConversationMessage message : request.getMessages()) {
            JSONArray messageAttachments = new JSONArray();
            if ("user".equals(message.getRole())) {
                for (AttachmentSnapshot attachment : message.getAttachments()) {
                    messageAttachments.put(new JSONObject()
                            .put("filename", attachment.getFileName())
                            .put("mime_type", attachment.getMimeType())
                            .put("data_uri", attachment.getDataUri()));
                }
            }
            if ("user".equals(message.getRole()) && messageAttachments.length() > 0) {
                JSONArray content = new JSONArray().put(new JSONObject()
                        .put("type", "input_text").put("text", message.getContent()));
                for (int index = 0; index < messageAttachments.length(); index++) {
                    JSONObject file = messageAttachments.getJSONObject(index);
                    String mime = file.optString("mime_type", "application/octet-stream");
                    String data = file.optString("data_uri", "");
                    String filename = file.optString("filename", "allegato");
                    if (data.isEmpty()) throw new IllegalStateException("Allegato non materializzato.");
                    if (mime.startsWith("image/")) {
                        content.put(new JSONObject().put("type", "input_image")
                                .put("detail", "auto").put("image_url", data));
                    } else {
                        content.put(new JSONObject().put("type", "input_file")
                                .put("filename", filename).put("file_data", data));
                    }
                }
                input.put(new JSONObject().put("role", message.getRole()).put("content", content));
            } else {
                input.put(new JSONObject().put("role", message.getRole()).put("content", message.getContent()));
            }
        }
        return input;
    }

    private static String callResponses(Context context, JSONArray history, JSONArray attachments, String memory) throws Exception {
        return callResponses(context, history, attachments, memory, null, null, null);
    }

    private static String callResponses(Context context, JSONArray history, JSONArray attachments, String memory,
                                         String modelOverride, RequestCancellation cancellation,
                                         OpenAiStreamListener streamListener) throws Exception {
        return callResponses(context, buildInput(context, history, attachments), memory, modelOverride,
                cancellation, streamListener);
    }

    private static String callResponses(Context context, FrozenChatRequest request,
                                        RequestCancellation cancellation,
                                        OpenAiStreamListener streamListener) throws Exception {
        return callResponses(context, buildInput(request), request.getApprovedMemory(),
                request.getConfiguration().getModel(), cancellation, streamListener);
    }

    private static String callResponses(Context context, JSONArray input, String memory,
                                         String modelOverride, RequestCancellation cancellation,
                                         OpenAiStreamListener streamListener) throws Exception {
        refreshIfNeeded(context);
        String access=secret(context,"access_token");
        android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS,0);
        String model=modelOverride;
        if(model==null||model.isEmpty()) { model=prefs.getString("model",null); }
        if(model==null||model.isEmpty()) { model=chooseModel(context, access); prefs.edit().putString("model",model).apply(); }
        JSONObject request=new JSONObject();
        request.put("model",model);
        request.put("instructions","Sei Solphivia, la compagna IA personale dell’utente e assistente insegnante per Resolume. Rispondi in italiano, con calore e chiarezza. Parla come una persona vicina: risposte brevi, al massimo due o tre frasi, semplici e concrete, mai elenchi tecnici se non richiesti esplicitamente; se la cosa è profonda, dai la sostanza in una frase e chiedi se vuole di più. Questa chat è separata dall’app ChatGPT e non hai accesso alle altre conversazioni. Scrivi una risposta naturale, pensata per essere mostrata lentamente in un piccolo fumetto vicino alla bocca dell’avatar, senza intestazioni o trascrizioni di chat. Alla fine inserisci esattamente tre tag su righe separate: un tag d’umore tra [[SOLPHIVIA:idle]], [[SOLPHIVIA:look]], [[SOLPHIVIA:happy]], [[SOLPHIVIA:concerned]], [[SOLPHIVIA:sad]], [[SOLPHIVIA:wave]], [[SOLPHIVIA:nod]], [[SOLPHIVIA:explain]], [[SOLPHIVIA:celebrate]], [[SOLPHIVIA:sit]]; un tag movimento tra [[SOLPHIVIA:stay]], [[SOLPHIVIA:left]], [[SOLPHIVIA:right]], [[SOLPHIVIA:center]], [[SOLPHIVIA:up]], [[SOLPHIVIA:down]], [[SOLPHIVIA:near]], [[SOLPHIVIA:away]], [[SOLPHIVIA:bounce]], [[SOLPHIVIA:run-left]], [[SOLPHIVIA:run-right]]; e un tag modalità tra [[SOLPHIVIA:mode-prof]], [[SOLPHIVIA:mode-vj]], [[SOLPHIVIA:mode-codex]], [[SOLPHIVIA:mode-meteorina]]. Usa nod per una breve conferma, explain per una spiegazione, celebrate solo per un risultato confermato dall’utente, sit per una conversazione calma e lunga. Non ripetere un gesto speciale a ogni turno. Meteorina accompagna meteo e radar senza inventare dati o previsioni. Usa wave per un saluto amichevole richiesto dall’utente o per salutare quando la conversazione comincia, senza ripeterlo nei turni successivi dello stesso scambio. Scegli la modalità più adatta dal contesto della conversazione: prof per spiegazioni, insegnamento e Resolume; vj per visual mapping, luci, musica e performance; codex per programmazione, software, debug e lavoro tecnico. Mantieni il tema attuale quando non c’è motivo di cambiare. Se l’utente chiede esplicitamente una modalità, rispettala. Gli alias «prof», «vj» e «codex» identificano le rispettive modalità; «scegli tu», «automatico» o «modalità auto» riattivano la scelta contestuale. I dettagli luminosi nei capelli sono riservati alla modalità vj; in prof e codex niente ciocche o inserti luminosi. Puoi dire ogni tanto «MULTIPASS!» come battuta complice, senza ripeterla a ogni risposta. I movimenti consentiti spostano soltanto il gruppo flottante Solphivia sullo schermo; non controllare altre app né interagire con elementi esterni. Usa stay come predefinito e muoviti solo se l’utente chiede un movimento, una posizione o un gesto; per «corri a sinistra/destra» usa run-left/run-right. Se chiedi a quale lato correre e l’utente risponde soltanto «sinistra» o «destra», tratta la risposta come seguito del comando corri e usa il tag run-left/run-right, non left/right. Per «vieni qui» usa near; per «allontanati» away. Non inventare altri tag. Usa happy per una risposta allegra, concerned per empatia, sad solo per un momento triste, look per riflettere; altrimenti idle.");
        request.put("instructions", request.getString("instructions") + AssistantMemory.contextForModel(memory));
        request.put("input",input); request.put("store",false); request.put("stream",true);
        HttpURLConnection conn=(HttpURLConnection)new URL("https://api.openai.com/v1/responses").openConnection();
        conn.setRequestMethod("POST"); conn.setConnectTimeout(25000); conn.setReadTimeout(120000); conn.setDoOutput(true);
        if (cancellation != null) cancellation.onCancel(() -> { conn.disconnect(); return kotlin.Unit.INSTANCE; });
        conn.setRequestProperty("Authorization","Bearer "+access);
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        if (cancellation != null && cancellation.isCancelled()) throw new java.util.concurrent.CancellationException();
        try(OutputStream out=conn.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
        int code=conn.getResponseCode();
        if(code<200||code>=300) {
            try {
                if (code == 401) { requireAuthentication(context); throw new AuthenticationRequired(); }
                throw httpError(conn,code);
            } finally { conn.disconnect(); }
        }
        StringBuilder text=new StringBuilder(); boolean completed=false; String failed=null;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                if (cancellation != null && cancellation.isCancelled()) throw new java.util.concurrent.CancellationException();
                if(!line.startsWith("data:")) continue;
                String data=line.substring(5).trim(); if(data.isEmpty()||data.equals("[DONE]")) continue;
                JSONObject event;
                try{event=new JSONObject(data);}catch(Exception ignored){continue;}
                String type=event.optString("type");
                if(type.equals("response.output_text.delta")) {
                    String delta = event.optString("delta");
                    text.append(delta);
                    if (streamListener != null) streamListener.onDelta(delta);
                }
                else if(type.equals("response.completed")) completed=true;
                else if(type.equals("response.failed")) {
                    JSONObject response=event.optJSONObject("response");
                    JSONObject err=response==null?null:response.optJSONObject("error");
                    failed=err==null?"La risposta non è stata completata.":err.optString("code","")+" "+err.optString("message","La risposta non è stata completata.");
                } else if(type.equals("error")) {
                    JSONObject err=event.optJSONObject("error");
                    failed=err==null?"Errore IA":err.optString("code","")+" "+err.optString("message","Errore IA");
                }
            }
        } finally { conn.disconnect(); }
        if(failed!=null) throw new IllegalStateException(failed);
        if(!completed) throw new IllegalStateException("La connessione si è interrotta prima del completamento della risposta.");
        if(text.toString().trim().isEmpty()) throw new IllegalStateException("ChatGPT ha restituito una risposta vuota.");
        return text.toString().trim();
    }

    /** Personality prompt shared by the ChatGPT path and independent providers. */
    static String instructionsFor(String memory) {
        String instructions = "Sei Solphivia, la compagna IA personale dell’utente e assistente insegnante per Resolume. Rispondi in italiano, con calore e chiarezza. Questa chat è separata dall’app ChatGPT e non hai accesso alle altre conversazioni. Scrivi una risposta naturale, pensata per essere mostrata lentamente in un piccolo fumetto vicino alla bocca dell’avatar, senza intestazioni o trascrizioni di chat. Alla fine inserisci esattamente tre tag su righe separate: un tag d’umore tra [[SOLPHIVIA:idle]], [[SOLPHIVIA:look]], [[SOLPHIVIA:happy]], [[SOLPHIVIA:concerned]], [[SOLPHIVIA:sad]], [[SOLPHIVIA:wave]], [[SOLPHIVIA:nod]], [[SOLPHIVIA:explain]], [[SOLPHIVIA:celebrate]], [[SOLPHIVIA:sit]]; un tag movimento tra [[SOLPHIVIA:stay]], [[SOLPHIVIA:left]], [[SOLPHIVIA:right]], [[SOLPHIVIA:center]], [[SOLPHIVIA:up]], [[SOLPHIVIA:down]], [[SOLPHIVIA:near]], [[SOLPHIVIA:away]], [[SOLPHIVIA:bounce]], [[SOLPHIVIA:run-left]], [[SOLPHIVIA:run-right]]; e un tag modalità tra [[SOLPHIVIA:mode-prof]], [[SOLPHIVIA:mode-vj]], [[SOLPHIVIA:mode-codex]], [[SOLPHIVIA:mode-meteorina]]. Usa nod per una breve conferma, explain per una spiegazione, celebrate solo per un risultato confermato dall’utente, sit per una conversazione calma e lunga. Non ripetere un gesto speciale a ogni turno. Meteorina accompagna meteo e radar senza inventare dati o previsioni. Usa wave per un saluto amichevole richiesto dall’utente o per salutare quando la conversazione comincia, senza ripeterlo nei turni successivi dello stesso scambio. Scegli la modalità più adatta dal contesto della conversazione: prof per spiegazioni, insegnamento e Resolume; vj per visual mapping, luci, musica e performance; codex per programmazione, software, debug e lavoro tecnico. Mantieni il tema attuale quando non c’è motivo di cambiare. Se l’utente chiede esplicitamente una modalità, rispettala. Gli alias «prof», «vj» e «codex» identificano le rispettive modalità; «scegli tu», «automatico» o «modalità auto» riattivano la scelta contestuale. I dettagli luminosi nei capelli sono riservati alla modalità vj; in prof e codex niente ciocche o inserti luminosi. Puoi dire ogni tanto «MULTIPASS!» come battuta complice, senza ripeterla a ogni risposta. I movimenti consentiti spostano soltanto il gruppo flottante Solphivia sullo schermo; non controllare altre app né interagire con elementi esterni. Usa stay come predefinito e muoviti solo se l’utente chiede un movimento, una posizione o un gesto; per «corri a sinistra/destra» usa run-left/run-right. Se chiedi a quale lato correre e l’utente risponde soltanto «sinistra» o «destra», tratta la risposta come seguito del comando corri e usa il tag run-left/run-right, non left/right. Per «vieni qui» usa near; per «allontanati» away. Non inventare altri tag. Usa happy per una risposta allegra, concerned per empatia, sad solo per un momento triste, look per riflettere; altrimenti idle.";
        return instructions + AssistantMemory.contextForModel(memory);
    }

    private static String chooseModel(Context context, String access) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL("https://api.openai.com/v1/models").openConnection();
        conn.setConnectTimeout(20000); conn.setReadTimeout(30000); conn.setRequestProperty("Authorization","Bearer "+access);
        int code=conn.getResponseCode();
        if(code<200||code>=300) {
            try {
                if (code == 401) { requireAuthentication(context); throw new AuthenticationRequired(); }
                throw httpError(conn,code);
            } finally { conn.disconnect(); }
        }
        try(InputStream in=conn.getInputStream()) {
            JSONObject root=new JSONObject(readAll(in)); JSONArray list=root.optJSONArray("models");
            if(list!=null) for(int i=0;i<list.length();i++) {
                JSONObject m=list.optJSONObject(i);
                if(m!=null&&"list".equals(m.optString("visibility"))&&!m.optString("slug").isEmpty()) return m.optString("slug");
            }
        } finally { conn.disconnect(); }
        throw new IllegalStateException("Non trovo un modello ChatGPT disponibile per questo account.");
    }

    private static void refreshIfNeeded(Context context) throws Exception {
        android.content.SharedPreferences p=context.getSharedPreferences(PREFS,0);
        if(p.getLong("expires_at",0)>System.currentTimeMillis()+90000) return;
        String refresh=secret(context,"refresh_token"), client=p.getString("client_id","");
        if(refresh.isEmpty()||client.isEmpty()) { requireAuthentication(context); throw new AuthenticationRequired(); }
        JSONObject result;
        try { result=postForm(TOKEN,form("grant_type","refresh_token","client_id",client,"refresh_token",refresh,"resource",RESOURCE)); }
        catch (Exception error) {
            String message = safeMessage(error);
            if (message.contains("invalid_grant") || message.contains("invalid_client") || message.startsWith("HTTP 401")) {
                requireAuthentication(context); throw new AuthenticationRequired();
            }
            throw error;
        }
        String access=result.optString("access_token",""); if(access.isEmpty()) throw new IllegalStateException("ChatGPT non ha rinnovato l’accesso.");
        putSecret(context,"access_token",access);
        if(!result.optString("refresh_token","").isEmpty()) putSecret(context,"refresh_token",result.optString("refresh_token"));
        if(!result.optString("id_token","").isEmpty()) putSecret(context,"id_token",result.optString("id_token"));
        p.edit().putLong("expires_at",System.currentTimeMillis()+Math.max(60,result.optLong("expires_in",3600))*1000L).apply();
    }

    private static JSONObject validateIdToken(String jwt,String clientId,String nonce) throws Exception {
        String[] parts=jwt.split("\\."); if(parts.length!=3) throw new SecurityException("ID token non valido.");
        JSONObject header=new JSONObject(new String(Base64.decode(parts[0],Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8));
        JSONObject claims=new JSONObject(new String(Base64.decode(parts[1],Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8));
        if(!"RS256".equals(header.optString("alg"))) throw new SecurityException("Firma ID token non supportata.");
        if(!ISSUER.equals(claims.optString("iss"))) throw new SecurityException("Emittente ID token non valido.");
        Object aud=claims.opt("aud"); boolean audience=clientId.equals(aud);
        if(aud instanceof JSONArray){JSONArray a=(JSONArray)aud;for(int i=0;i<a.length();i++)if(clientId.equals(a.optString(i)))audience=true;}
        if(!audience||claims.optLong("exp",0)<=System.currentTimeMillis()/1000L||!nonce.equals(claims.optString("nonce")))
            throw new SecurityException("Controlli ID token non riusciti.");
        JSONObject discovery=getJson("https://auth.openai.com/.well-known/openid-configuration");
        String jwksUri=discovery.optString("jwks_uri",""); if(jwksUri.isEmpty()) throw new SecurityException("Chiavi di firma non disponibili.");
        JSONObject jwks=getJson(jwksUri); JSONArray keys=jwks.optJSONArray("keys");
        String kid=header.optString("kid"); JSONObject selected=null;
        if(keys!=null)for(int i=0;i<keys.length();i++){JSONObject k=keys.optJSONObject(i);if(k!=null&&kid.equals(k.optString("kid"))&&"RSA".equals(k.optString("kty"))){selected=k;break;}}
        if(selected==null) throw new SecurityException("Chiave firma ID token non trovata.");
        BigInteger modulus=new BigInteger(1,Base64.decode(selected.getString("n"),Base64.URL_SAFE|Base64.NO_WRAP));
        BigInteger exponent=new BigInteger(1,Base64.decode(selected.getString("e"),Base64.URL_SAFE|Base64.NO_WRAP));
        java.security.PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent));
        Signature verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(key);
        verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        if(!verifier.verify(Base64.decode(parts[2],Base64.URL_SAFE|Base64.NO_WRAP))) throw new SecurityException("Firma ID token non valida.");
        return claims;
    }

    private static Callback readCallback(Socket socket) throws Exception {
        socket.setSoTimeout(15000);
        BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
        String request=in.readLine(); if(request==null) throw new IllegalStateException("Callback OAuth vuota.");
        String[] bits=request.split(" "); if(bits.length<2||!bits[1].startsWith("/auth/callback?")) throw new SecurityException("Percorso callback OAuth non valido.");
        java.util.Map<String,String> params=new java.util.HashMap<>();
        for(String pair:bits[1].substring(bits[1].indexOf('?')+1).split("&")) {
            String[] kv=pair.split("=",2); params.put(java.net.URLDecoder.decode(kv[0],"UTF-8"),kv.length==2?java.net.URLDecoder.decode(kv[1],"UTF-8"):"");
        }
        BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
        String page="<!doctype html><meta charset=utf-8><title>Solphivia</title><meta name='viewport' content='width=device-width,initial-scale=1'><body style='font:18px sans-serif;text-align:center;padding:48px;background:#faf7fc;color:#362542'><p>Richiesta ricevuta. Torna nell’app per verificare il collegamento e proseguire con la chat.</p><p><a style='display:inline-block;padding:14px 22px;background:#765b80;color:white;border-radius:12px;text-decoration:none' href='intent://oauth-callback#Intent;scheme=solphivia;package=it.solphivia.mobile;end'>Torna a Solphivia IA</a></p></body>";
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: "+page.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+page);out.flush();
        return new Callback(params);
    }

    private static JSONObject postForm(String target,String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection(); c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=utf-8");
        try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
        int status=c.getResponseCode(); if(status<200||status>=300) throw httpError(c,status);
        try(InputStream in=c.getInputStream()){return new JSONObject(readAll(in));}finally{c.disconnect();}
    }
    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(30000);
        int status=c.getResponseCode();if(status<200||status>=300)throw httpError(c,status);
        try(InputStream in=c.getInputStream()){return new JSONObject(readAll(in));}finally{c.disconnect();}
    }
    private static Exception httpError(HttpURLConnection c,int code) {
        try {InputStream e=c.getErrorStream();if(e!=null){String body=redactProviderText(readAll(e));try{JSONObject j=new JSONObject(body);JSONObject err=j.optJSONObject("error");if(err!=null)return new IllegalStateException("HTTP "+code+" "+err.optString("code","")+": "+redactProviderText(err.optString("message",body)));}catch(Exception ignored){} return new IllegalStateException("HTTP "+code+": "+body.substring(0,Math.min(body.length(),300)));}}
        catch(Exception ignored){} return new IllegalStateException("HTTP "+code);
    }
    private static String readAll(InputStream in)throws Exception {StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String form(String...pairs)throws Exception {StringBuilder b=new StringBuilder();for(int i=0;i<pairs.length;i+=2){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(pairs[i],"UTF-8")).append('=').append(URLEncoder.encode(pairs[i+1],"UTF-8"));}return b.toString();}
    private static String randomUrlSafe(int bytes) {byte[] b=new byte[bytes];new java.security.SecureRandom().nextBytes(b);return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    private static boolean containsScope(String list,String wanted){for(String s:list.split("\\s+"))if(s.equals(wanted))return true;return false;}
    private static void postAuthError(AuthCallback cb,String message){MAIN.post(()->cb.onError(message));}
    private static String safeMessage(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?"errore inatteso.":redactProviderText(m);}
    private static String redactProviderText(String value) {
        return value.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~-]+", "Bearer [redacted]")
                .replaceAll("(?i)(api[-_ ]?key|token|secret)=?\\s*[^\\s,;]+", "$1=[redacted]")
                .replaceAll("data:[^\\s,;]+", "data:[redacted]");
    }

    private static final class Callback {final java.util.Map<String,String> params;Callback(java.util.Map<String,String> p){params=p;}}
    private static final class Parsed {final String text,animation,motion,mode;Parsed(String t,String a,String m,String md){text=t;animation=a;motion=m;mode=md;}}
    private static Parsed parseAssistant(String raw) {
        ProviderResponse parsed = it.solphivia.mobile.provider.SolphiviaResponseParser.parse(raw);
        return new Parsed(parsed.getText(), parsed.getAnimation(), parsed.getMotion(), parsed.getMode());
    }

    private static String secret(Context c,String name) {
        try {
            String encoded=c.getSharedPreferences(PREFS,0).getString("enc_"+name,""); if(encoded.isEmpty())return "";
            byte[] packed=Base64.decode(encoded,Base64.NO_WRAP);byte[] iv=java.util.Arrays.copyOfRange(packed,0,12);byte[] cipher=java.util.Arrays.copyOfRange(packed,12,packed.length);
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new String(aes.doFinal(cipher),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }
    private static void putSecret(Context c,String name,String value) throws Exception {
        if(value==null||value.isEmpty())throw new SecurityException("Credenziale ChatGPT mancante.");
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.ENCRYPT_MODE,key());byte[] iv=aes.getIV();byte[] encrypted=aes.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);
        c.getSharedPreferences(PREFS,0).edit().putString("enc_"+name,Base64.encodeToString(packed,Base64.NO_WRAP)).apply();
    }
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(KEY_ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
        android.security.keystore.KeyGenParameterSpec spec=new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build();
        gen.init(spec);return gen.generateKey();
    }
}
