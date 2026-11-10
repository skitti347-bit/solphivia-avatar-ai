package it.solphivia.mobile;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Microsoft Edge "read aloud" TTS (free API): WebSocket synthesis, female neural voice.
 *  No external dependencies: hand-rolled minimal RFC 6455 client over TLS.
 *  Default voice it-IT-ElsaNeural (female); English fallback en-US-AriaNeural (female). */
final class EdgeTts {
    static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    static final String GEC_VERSION = "2-143.0.3650.75";
    static final String HOST = "speech.platform.bing.com";
    static final String DEFAULT_VOICE = "it-IT-ElsaNeural";
    static final String ENGLISH_VOICE = "en-US-AriaNeural";

    /** Lifecycle callbacks, always delivered on the main thread. */
    interface Listener {
        void onDone();
        void onError(String message);
    }

    private static final Object LOCK = new Object();
    private static java.util.concurrent.ExecutorService worker;
    private static Handler playerHandler;
    private static MediaPlayer player;
    private static int generation;
    private static String speakingText = "";
    private static volatile boolean busy;

    private EdgeTts() { }

    static void speak(Context context, String text, String voice, int ratePercent, Listener listener) {
        String prepared = SpeechText.prepare(text == null ? "" : text, false, true).trim();
        synchronized (LOCK) {
            cancelLocked();
            generation++;
            final int gen = generation;
            busy = true;
            speakingText = prepared;
            if (worker == null) {
                worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "edge-tts");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (playerHandler == null) {
                HandlerThread thread = new HandlerThread("edge-tts-player");
                thread.start();
                playerHandler = new Handler(thread.getLooper());
            }
            final Context app = context.getApplicationContext();
            final String chosen = voice == null || voice.isEmpty() ? DEFAULT_VOICE : voice;
            worker.execute(() -> runChunks(app, gen, prepared, chosen, ratePercent, listener));
        }
    }

    static void stop() {
        synchronized (LOCK) {
            cancelLocked();
            generation++;
            busy = false;
            speakingText = "";
        }
    }

    static boolean busy() { return busy; }

    static String spokenText() { return speakingText; }

    private static void cancelLocked() {
        if (playerHandler == null) return; // nessuna riproduzione precedente
        playerHandler.post(() -> {
            MediaPlayer current = player;
            player = null;
            if (current != null) {
                try { current.stop(); } catch (RuntimeException ignored) { }
                try { current.release(); } catch (RuntimeException ignored) { }
            }
        });
    }

    /** Synthesizes and plays the text chunk by chunk, sequentially, on the worker thread. */
    private static void runChunks(Context app, int gen, String text, String voice, int ratePercent, Listener listener) {
        List<String> chunks = SpeechText.chunks(text, 800);
        if (chunks.isEmpty()) {
            finish(gen, listener, null);
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (gen != generation) return;
            File file = new File(app.getCacheDir(), "edge-tts-" + gen + "-" + i + ".mp3");
            try {
                if (file.exists()) file.delete();
                synthesizeToFile(chunks.get(i), voice, ratePercent, file);
            } catch (Exception error) {
                file.delete();
                if (gen == generation) finish(gen, listener, describe(error));
                return;
            }
            if (gen != generation) { file.delete(); return; }
            String error = playAndWait(file);
            file.delete();
            if (error != null) { if (gen == generation) finish(gen, listener, error); return; }
        }
        finish(gen, listener, null);
    }

    private static void finish(int gen, Listener listener, String error) {
        busy = false;
        if (gen != generation) return;
        speakingText = "";
        new Handler(Looper.getMainLooper()).post(() -> {
            if (error == null) listener.onDone();
            else listener.onError(error);
        });
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** Connects, sends speech.config + SSML, writes every audio frame to the file until turn.end. */
    private static void synthesizeToFile(String text, String voice, int ratePercent, File out) throws Exception {
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(HOST, 443);
        socket.setSoTimeout(10000);
        try {
            socket.startHandshake();
            OutputStream outStream = socket.getOutputStream();
            InputStream inStream = socket.getInputStream();
            long now = System.currentTimeMillis() / 1000L;
            String connectionId = requestId();
            outStream.write((
                    "GET " + handShakePath(now, connectionId) + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + secWebSocketKey() + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0\r\n"
                    + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                    + "Accept-Encoding: gzip, deflate, br\r\n"
                    + "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7\r\n"
                    + "Cookie: muid=" + requestId().toUpperCase(Locale.US) + ";\r\n"
                    + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            outStream.flush();
            String response = readHttpHeader(inStream);
            if (!response.contains(" 101 ")) throw new IllegalStateException("Handshake rifiutato: " + firstLine(response));

            sendTextFrame(outStream, speechConfigMessage());
            sendTextFrame(outStream, ssmlMessage(text, voice, ratePercent));

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            while (true) {
                int header = inStream.read();
                if (header < 0) throw new IllegalStateException("Connessione chiusa dal server");
                int opcode = header & 0x0F;
                boolean fin = (header & 0x80) != 0;
                long length = inStream.read();
                if (length < 0) throw new IllegalStateException("Connessione chiusa dal server");
                length &= 0x7F;
                if (length == 126) {
                    int hi = inStream.read(), lo = inStream.read();
                    if (lo < 0) throw new IllegalStateException("Frame troncato");
                    length = ((hi & 0xFF) << 8) | (lo & 0xFF);
                } else if (length == 127) {
                    length = 0;
                    for (int b = 0; b < 8; b++) {
                        int next = inStream.read();
                        if (next < 0) throw new IllegalStateException("Frame troncato");
                        length = (length << 8) | (next & 0xFF);
                    }
                }
                byte[] payload = new byte[(int) length];
                int read = 0;
                while (read < payload.length) {
                    int next = inStream.read(payload, read, payload.length - read);
                    if (next < 0) throw new IllegalStateException("Payload troncato");
                    read += next;
                }
                if (opcode == 0x8) break; // close
                if (opcode == 0x9) { // ping → pong
                    outStream.write(pongFrame(payload));
                    outStream.flush();
                    continue;
                }
                if (opcode != 0x1 && opcode != 0x2) continue; // ignore others
                pending.write(payload, 0, payload.length);
                if (!fin) continue;
                byte[] message = pending.toByteArray();
                pending.reset();
                if (opcode == 0x2) {
                    byte[] sound = audioOf(message);
                    audio.write(sound, 0, sound.length);
                } else {
                    String textMessage = new String(message, java.nio.charset.StandardCharsets.UTF_8);
                    if ("turn.end".equals(pathOf(textMessage))) break;
                }
            }
            byte[] mp3 = audio.toByteArray();
            if (mp3.length == 0) throw new IllegalStateException("Nessun audio ricevuto");
            try (FileOutputStream fileStream = new FileOutputStream(out)) {
                fileStream.write(mp3);
            }
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    static String firstLine(String response) {
        if (response == null) return "";
        int end = response.indexOf("\r\n");
        return end < 0 ? response : response.substring(0, end);
    }

    static String handShakePath(long unixSeconds, String connectionId) {
        return "/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + SecMsGecV2.token(unixSeconds)
                + "&Sec-MS-GEC-Version=" + GEC_VERSION
                + "&ConnectionId=" + connectionId;
    }

    static String speechConfigMessage() {
        return "X-Timestamp:" + timestamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
    }

    static String ssmlMessage(String text, String voice, int ratePercent) {
        return "X-RequestId:" + requestId() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssmlFor(text, voice, ratePercent);
    }

    static String timestamp() {
        return new java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
                .format(new java.util.Date());
    }

    static String requestId() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    static String secWebSocketKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    /** SSML wrapper: female neural voice, rate offset from the saved percent. */
    static String ssmlFor(String text, String voice, int ratePercent) {
        int rate = Math.max(-50, Math.min(50, ratePercent - 100));
        String offset = (rate >= 0 ? "+" : "") + rate + "%";
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='"
                + (voice.startsWith("it") ? "it-IT" : "en-US")
                + "'><voice name='" + voice + "'><prosody pitch='+0Hz' rate='" + offset
                + "' volume='+0%'>" + escapeXml(text) + "</prosody></voice></speak>";
    }

    static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Sec-MS-GEC v2 DRM token lives in SecMsGecV2 (CS64-SHA256, goes in the query string only). */

    /** Binary audio frame = 2-byte big-endian header length + header text + raw MP3 bytes. */
    static byte[] audioOf(byte[] frame) {
        if (frame.length < 2) return new byte[0];
        int headerLength = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        if (headerLength + 2 > frame.length) return new byte[0];
        byte[] audio = new byte[frame.length - headerLength - 2];
        System.arraycopy(frame, headerLength + 2, audio, 0, audio.length);
        return audio;
    }

    /** Extracts the Path value ("ssml", "turn.end", …) from a server text message. */
    static String pathOf(String message) {
        int pathIndex = message.indexOf("Path:");
        if (pathIndex < 0) return "";
        int start = pathIndex + 5;
        int end = start;
        while (end < message.length() && message.charAt(end) != '\r' && message.charAt(end) != '\n') end++;
        return message.substring(start, end).trim();
    }

    private static String readHttpHeader(InputStream stream) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = stream.read();
            if (current < 0) break;
            header.write(current);
            if (previous == '\r' && current == '\n') {
                String text = header.toString("US-ASCII");
                if (text.endsWith("\r\n\r\n")) return text;
            }
            previous = current;
        }
        return header.toString("US-ASCII");
    }

    private static void sendTextFrame(OutputStream outStream, String payload) throws Exception {
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text
        if (data.length < 126) {
            frame.write(0x80 | data.length);
        } else if (data.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((data.length >> 8) & 0xFF);
            frame.write(data.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((data.length >> shift) & 0xFF));
        }
        frame.write(mask);
        for (int i = 0; i < data.length; i++) frame.write(data[i] ^ mask[i % 4]);
        outStream.write(frame.toByteArray());
        outStream.flush();
    }

    private static byte[] pongFrame(byte[] pingPayload) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x8A); // FIN + pong
        frame.write(pingPayload.length & 0x7F);
        frame.write(pingPayload, 0, pingPayload.length);
        return frame.toByteArray();
    }

    /** Plays one MP3 file to completion on the player thread; returns null or an error message. */
    private static String playAndWait(File file) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final String[] error = {null};
        playerHandler.post(() -> {
            if (player != null) { try { player.release(); } catch (RuntimeException ignored) { } }
            MediaPlayer current = new MediaPlayer();
            player = current;
            try {
                FileInputStream stream = new FileInputStream(file);
                try { current.setDataSource(stream.getFD()); } finally { try { stream.close(); } catch (Exception ignored) { } }
                current.setOnCompletionListener(mp -> {
                    if (player == mp) player = null;
                    mp.release();
                    done.countDown();
                });
                current.setOnErrorListener((mp, what, extra) -> {
                    if (player == mp) player = null;
                    error[0] = "Riproduzione audio non riuscita (" + what + ")";
                    mp.release();
                    done.countDown();
                    return true;
                });
                current.prepare();
                current.start();
            } catch (Exception failure) {
                if (player == current) player = null;
                try { current.release(); } catch (RuntimeException ignored) { }
                error[0] = describe(failure);
                done.countDown();
            }
        });
        try {
            if (!done.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                playerHandler.post(() -> {
                    MediaPlayer current = player;
                    player = null;
                    if (current != null) {
                        try { current.stop(); } catch (RuntimeException ignored) { }
                        try { current.release(); } catch (RuntimeException ignored) { }
                    }
                });
                return "Riproduzione troppo lunga: annullata.";
            }
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
            return "Riproduzione interrotta.";
        }
        return error[0];
    }
}
