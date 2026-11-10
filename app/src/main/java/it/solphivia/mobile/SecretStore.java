package it.solphivia.mobile;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keystore-backed storage for the third-party provider API key, with the same
 * AES-256-GCM pattern used for the local memory and the ChatGPT tokens.
 * The key never leaves the device KeyStore; only ciphertext reaches preferences.
 */
final class SecretStore {
    private static final String KEY_ALIAS = "solphivia-provider-key-v1";
    private static final String PREFS = "provider";
    private static final String PREF_FIELD = "api_key_enc";

    private SecretStore() {}

    static synchronized String read(Context context) {
        try {
            String stored = context.getSharedPreferences(PREFS, 0).getString(PREF_FIELD, "");
            if (stored.isEmpty()) return "";
            byte[] sealed = Base64.decode(stored, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, localKey(), new GCMParameterSpec(128, java.util.Arrays.copyOf(sealed, 12)));
            return new String(cipher.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void write(Context context, String value) {
        try {
            if (value == null) value = "";
            if (value.isEmpty()) {
                context.getSharedPreferences(PREFS, 0).edit().remove(PREF_FIELD).apply();
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, localKey());
            byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[12 + sealed.length];
            System.arraycopy(cipher.getIV(), 0, out, 0, 12);
            System.arraycopy(sealed, 0, out, 12, sealed.length);
            context.getSharedPreferences(PREFS, 0).edit()
                    .putString(PREF_FIELD, Base64.encodeToString(out, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Chiave non salvata.", e);
        }
    }

    private static SecretKey localKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }
}
