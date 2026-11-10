package it.solphivia.mobile;

/** Sec-MS-GEC v2 token for the Edge TTS DRM ("<16hex>-<64hex>", CS64-SHA256).
 *  Faithful port of edge-tts 7.3.1 drm.py (verified against its test vectors). */
final class SecMsGecV2 {
    private static final int MASK = 0xFFFFFFFF;
    private static final long LMASK = 0xFFFFFFFFL;

    private static final int[] SHA256_INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19 };
    /** Non-standard starting state for the salted round pass. */
    private static final int[] SHA256_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2 };
    /** 14 precomputed schedule words for the salted expansion (indices 16..29). */
    private static final int[] SHA256_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
            0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
            0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
            0xD258B060, 0x72766564 };
    private static final int[] K = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2 };
    /** K[14] is swapped for this in the salted pass. */
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
    private static final int SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static final long[] CS64_COEFFICIENTS = {
            0xE82E91B1B32700D0L, 0x44DE24C688DFF51DL, 0x3BEB65BC29514038L, 0xC89C5933B328CBDFL,
            0x8D1EC211D405B4D5L, 0x314EF5D526E55F3CL, 0xE92491B1607801C6L, 0xF0A5B9D582B2C1FBL,
            0x06064CF3FA51A3DAL, 0xDA79FA60207CC464L, 0xBED205603C3C74F3L, 0x89F9C9F8D2D14B61L,
            0x35FA38C6BB04A2C1L, 0xCB49E3C6C5626167L };
    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJUSTMENT0 = 0xF2F90B1A;
    private static final int CS64_ADJUSTMENT1 = 0x0B1A0000;

    private SecMsGecV2() { }

    /** Token for a Unix timestamp (seconds): "<CS64 hex>-<SHA-256 hex>", both upper-case. */
    static String token(long unixSeconds) {
        long ticks = unixSeconds + 11644473600L;
        ticks -= ticks % 300L;
        ticks *= 10_000_000L;
        byte[] data = (Long.toString(ticks) + EdgeTts.TRUSTED_TOKEN)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        long[] cs64 = cs64Digest(data, digest);
        return hex32(cs64[0]) + hex32(cs64[1]) + "-" + hex(digest);
    }

    /** SHA-256 small sigma zero / one, combined rotations truncated once (NOT the standard sigmas). */
    private static int sigma0(int v) {
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & MASK;
    }

    private static int sigma1(int v) {
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & MASK;
    }

    /** One SHA-256 round pass over 64 words with the given constants, optionally resuming mid-block. */
    private static int[] rounds(int[] state, int[] words, int[] constants, int firstRound) {
        int a = state[0], b = state[1], c = state[2], d = state[3];
        int e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = firstRound; i < 64; i++) {
            int t1 = h + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11)
                    ^ Integer.rotateRight(e, 25)) + (g ^ (e & (f ^ g))) + constants[i] + words[i];
            int t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13)
                    ^ Integer.rotateRight(a, 22)) + ((a & b) | (c & (a | b)));
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        return new int[] {a, b, c, d, e, f, g, h};
    }

    /** Standard constants, full pass. */
    private static int[] rounds(int[] state, int[] words) {
        return rounds(state, words, K, 0);
    }

    private static int[] expand(int[] words, int from) {
        int[] out = new int[64];
        System.arraycopy(words, 0, out, 0, Math.min(words.length, 64));
        for (int i = from; i < 64; i++) {
            int x = out[i - 2], y = out[i - 15];
            out[i] = sigma1(x) + out[i - 7] + sigma0(y) + out[i - 16] & MASK;
        }
        return out;
    }

    /** Initial state for the token digest: a salted SHA-256 pass over the first 7 data bytes. */
    private static int[] sha256Init(byte[] salt) {
        // words[0..13] = 0, words[14] = salt[0:3] BE, words[15] = salt[3:7] BE
        int[] words = new int[16];
        words[14] = ((salt[0] & 0xFF) << 16) | ((salt[1] & 0xFF) << 8) | (salt[2] & 0xFF);
        words[15] = ((salt[3] & 0xFF) << 24) | ((salt[4] & 0xFF) << 16)
                | ((salt[5] & 0xFF) << 8) | (salt[6] & 0xFF);
        // Modified expansion for words 16..29: precomputed schedule + partial additions.
        for (int i = 16; i < 30; i++) {
            int value = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                value += i == 16 ? (sigma1(words[i - 2]) ^ SIGMA1_ADJUSTMENT) : sigma1(words[i - 2]);
            }
            if (i - 7 >= 14) value += words[i - 7];
            if (i - 15 >= 14) {
                value += i == 29 ? (sigma0(words[i - 15]) ^ SIGMA0_ADJUSTMENT) : sigma0(words[i - 15]);
            }
            int[] grown = new int[i + 1];
            System.arraycopy(words, 0, grown, 0, i);
            grown[i] = value & MASK;
            words = grown;
        }
        // words[30]: closure of the salted block.
        int[] grown = new int[31];
        System.arraycopy(words, 0, grown, 0, 30);
        grown[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
                + SHA256_ADJUSTMENT - K[14]) & MASK;
        int[] full = expand(grown, 31);
        // Salted pass: K[14] is swapped for the adjustment constant.
        int[] blockK = K.clone();
        blockK[14] = SHA256_ADJUSTMENT;
        int[] working = rounds(SHA256_STATE, full, blockK, 14);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = SHA256_INITIAL[i] + working[i] & MASK;
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int paddedLength = data.length - 7 + 1 + ((55 - length) % 64 + 64) % 64 + 8;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bitLength = (long) length * 8;
        for (int i = 0; i < 8; i++) {
            padded[paddedLength - 1 - i] = (byte) (bitLength >>> (8 * i));
        }
        int[] state = sha256Init(java.util.Arrays.copyOf(data, 7));
        for (int offset = 0; offset < paddedLength; offset += 64) {
            int[] words = new int[64];
            for (int i = 0; i < 16; i++) {
                int base = offset + 4 * i;
                words[i] = ((padded[base] & 0xFF) << 24) | ((padded[base + 1] & 0xFF) << 16)
                        | ((padded[base + 2] & 0xFF) << 8) | (padded[base + 3] & 0xFF);
            }
            words = expand(words, 16);
            int[] working = rounds(state, words);
            for (int i = 0; i < 8; i++) state[i] = state[i] + working[i] & MASK;
        }
        byte[] digest = new byte[32];
        for (int i = 0; i < 8; i++) {
            digest[4 * i] = (byte) (state[i] >>> 24);
            digest[4 * i + 1] = (byte) (state[i] >>> 16);
            digest[4 * i + 2] = (byte) (state[i] >>> 8);
            digest[4 * i + 3] = (byte) state[i];
        }
        return digest;
    }

    private static int rol16(int v) {
        return Integer.rotateLeft(v & MASK, 16);
    }

    /** CS64 digest: (swap_last ^ state, swap_acc ^ acc) as two u32s. */
    private static long[] cs64Digest(byte[] data, byte[] digest) {
        long keyA = (le32(digest, 0) | 1) & LMASK;
        long keyB = (le32(digest, 4) | 1) & LMASK;
        int factorA = (int) (keyA * CS64_FACTOR & LMASK);
        int factorB = (int) (keyB * CS64_FACTOR & LMASK);
        // Words of (0x00 + data) little-endian, zero-padded to an even word count.
        int total = data.length + 1;
        int padded = total + (4 - total % 4) % 4;
        int wordCount = padded / 4;
        if (wordCount % 2 != 0) wordCount++;
        int[] terms = new int[14 + wordCount];
        int[] offsets = new int[14 + wordCount];
        for (int i = 0; i < 14; i++) {
            long pair = CS64_COEFFICIENTS[i];
            int coefficient = (int) (pair >>> 32);
            terms[i] = (int) ((long) coefficient * (i % 2 == 0 ? factorA : factorB) & LMASK);
            offsets[i] = (int) (pair & LMASK);
        }
        for (int index = 0; index < wordCount; index++) {
            long word = 0;
            for (int b = 0; b < 4; b++) {
                int pos = 4 * index + b; // position in the (0x00 + data) array
                int value = pos == 0 ? 0 : pos <= data.length ? data[pos - 1] & 0xFF : 0;
                word |= (long) value << (8 * b);
            }
            int w = (int) word;
            long term = w * (index % 2 == 0 ? keyA : keyB) & LMASK;
            long offset = (long) rol16(w) * (index % 2 == 0 ? 0xEF0569FB : 0xC31713DB) & LMASK;
            if (index == 0) {
                term += (long) CS64_ADJUSTMENT0 * factorA;
                offset += CS64_ADJUSTMENT1;
            }
            terms[14 + index] = (int) (term & LMASK);
            offsets[14 + index] = (int) (offset & LMASK);
        }
        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int index = 0; index < terms.length; index += 2) {
            int x = terms[index], y = terms[index + 1];
            int t = rol16(offsets[index] + x);
            int q = (int) ((long) t * 0x76F50F34 & LMASK);
            swapAcc = (swapAcc + ((long) rol16(q) * 0xC3EFEA97 + q)) & LMASK;
            t = rol16(offsets[index + 1] + y);
            q = (int) ((long) t * 0x1881CE3C & LMASK);
            swapLast = ((long) rol16(q) * 0x35BD1EC9 + q) & LMASK;
            swapAcc = (swapAcc + swapLast) & LMASK;

            state = (state * keyA + x) & LMASK;
            state = (long) rol16((int) state) * 0xCF98B111 & LMASK;
            state = (long) rol16((int) state) * 0x87085B9F & LMASK;
            state = (long) rol16((int) state) * 0x12CEB96D & LMASK;
            state = (long) rol16((int) state) * 0x257E1D83 & LMASK;
            acc = (acc + state) & LMASK;
            t = (int) ((state * keyB + y) & LMASK);
            t = (int) ((long) rol16(t) * 0xA27416F5 & LMASK);
            t = (int) ((long) rol16(t) * 0xD38396FF & LMASK);
            state = (long) rol16((int) ((long) rol16(t) * 0x7C932B89 & LMASK)) * 0xBFA49F69 & LMASK;
            acc = (acc + state) & LMASK;
        }
        return new long[] {(swapLast ^ state) & MASK, (swapAcc ^ acc) & MASK};
    }

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16 | (long) (data[offset + 3] & 0xFF) << 24;
    }

    /** u32 as little-endian byte hex, matching struct.pack("<I").hex(). */
    private static String hex32(long word) {
        StringBuilder hex = new StringBuilder(8);
        for (int i = 0; i < 4; i++) {
            hex.append(String.format(java.util.Locale.US, "%02X", (word >>> (8 * i)) & 0xFF));
        }
        return hex.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(java.util.Locale.US, "%02X", b));
        return hex.toString();
    }
}
