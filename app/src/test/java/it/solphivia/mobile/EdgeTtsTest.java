package it.solphivia.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-logic tests for the Edge TTS client (token, SSML, WS message parsing). */
public class EdgeTtsTest {

    @Test public void secMsGecV2_matchesEdgeTtsReferenceVectors() {
        // Expected values from edge-tts 7.3.1 generate_sec_ms_gec_v2 (CS64-SHA256, "<16hex>-<64hex>").
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
                SecMsGecV2.token(1700000000L));
        assertEquals("3C30458C5659B332-E5DCF5A9F6EB5EC4ED5F165556AB4314803BC0D7D4F996330FB4760A9947D562",
                SecMsGecV2.token(1762738800L));
        assertEquals("5E9153719FADA922-14D2A75113EC5862E17303287DCDB197E3A671DF9FCC53C100D6B27928384B98",
                SecMsGecV2.token(1000000000L));
    }

    @Test public void secMsGecV2_isStableWithinFiveMinuteBucket() {
        // 1700000000 and 1700000050 fall in the same 5-minute bucket (boundary at +100).
        assertEquals(SecMsGecV2.token(1700000000L), SecMsGecV2.token(1700000050L));
    }

    @Test public void secMsGecV2_changesAcrossBucketBoundary() {
        assertTrue(!SecMsGecV2.token(1700000000L).equals(SecMsGecV2.token(1700000301L)));
    }

    @Test public void secMsGecV2_is16Dash64UppercaseHex() {
        String token = SecMsGecV2.token(System.currentTimeMillis() / 1000L);
        assertEquals(81, token.length());
        assertTrue(token.matches("[0-9A-F]{16}-[0-9A-F]{64}"));
    }

    @Test public void handShakePath_carriesV2TokenVersionAndConnectionId() {
        String path = EdgeTts.handShakePath(1700000000L, "00112233445566778899aabbccddeeff");
        assertTrue(path.contains("TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"));
        assertTrue(path.contains("&Sec-MS-GEC=2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2"));
        assertTrue(path.contains("&Sec-MS-GEC-Version=2-143.0.3650.75"));
        assertTrue(path.endsWith("&ConnectionId=00112233445566778899aabbccddeeff"));
    }

    @Test public void ssml_wrapsFemaleItalianVoiceWithRateOffset() {
        String ssml = EdgeTts.ssmlFor("Ciao, come stai?", EdgeTts.DEFAULT_VOICE, 110);
        assertTrue(ssml.contains("<voice name='it-IT-ElsaNeural'>"));
        assertTrue(ssml.contains("xml:lang='it-IT'"));
        assertTrue(ssml.contains("rate='+10%'"));
    }

    @Test public void ssml_escapesXmlSensitiveCharacters() {
        String ssml = EdgeTts.ssmlFor("A & B < C > D", EdgeTts.DEFAULT_VOICE, 100);
        assertTrue(ssml.contains("A &amp; B &lt; C &gt; D"));
        assertTrue(!ssml.contains(" A & B"));
    }

    @Test public void ssml_rateIsClampedToServerAcceptedRange() {
        assertTrue(EdgeTts.ssmlFor("test", EdgeTts.DEFAULT_VOICE, 400).contains("rate='+50%'"));
        assertTrue(EdgeTts.ssmlFor("test", EdgeTts.DEFAULT_VOICE, 10).contains("rate='-50%'"));
    }

    @Test public void pathOf_readsThePathHeader() {
        assertEquals("turn.end", EdgeTts.pathOf(
                "X-RequestId:abcd\r\nContent-Type:application/json\r\nPath:turn.end\r\n\r\n{}"));
        assertEquals("ssml", EdgeTts.pathOf("Path:ssml\r\n\r\n<speak/>"));
        assertEquals("", EdgeTts.pathOf("X-RequestId:abcd\r\n\r\n"));
    }

    @Test public void audioOf_stripsTwoByteHeaderAndKeepsMp3Payload() {
        byte[] header = "X-RequestId:abc;Path:audio\r\n".getBytes();
        byte[] mp3 = {0x49, 0x44, 0x33, 0x04}; // "ID3" + version: plausible MP3 start
        byte[] frame = new byte[2 + header.length + mp3.length];
        frame[0] = (byte) ((header.length >> 8) & 0xFF);
        frame[1] = (byte) (header.length & 0xFF);
        System.arraycopy(header, 0, frame, 2, header.length);
        System.arraycopy(mp3, 0, frame, 2 + header.length, mp3.length);
        byte[] audio = EdgeTts.audioOf(frame);
        assertEquals(mp3.length, audio.length);
        for (int i = 0; i < mp3.length; i++) assertEquals(mp3[i], audio[i]);
    }

    @Test public void audioOf_returnsEmptyOnMalformedFrames() {
        assertEquals(0, EdgeTts.audioOf(new byte[]{0x00}).length);
        assertEquals(0, EdgeTts.audioOf(new byte[]{0x00, 0x7F, 0x01}).length);
        assertEquals(0, EdgeTts.audioOf(new byte[0]).length);
    }

    @Test public void speechConfigMessage_declaresMp3OutputAndConfigPath() {
        String message = EdgeTts.speechConfigMessage();
        assertTrue(message.contains("Path:speech.config"));
        assertTrue(message.contains("audio-24khz-48kbitrate-mono-mp3"));
    }

    @Test public void ssmlMessage_carriesRequestIdAndSsmlPath() {
        String message = EdgeTts.ssmlMessage("Ciao", EdgeTts.DEFAULT_VOICE, 100);
        assertTrue(message.contains("Content-Type:application/ssml+xml"));
        assertTrue(message.contains("Path:ssml"));
        assertTrue(message.endsWith("</voice></speak>"));
    }

    @Test public void englishVoiceIsAlsoFemale() {
        assertEquals("en-US-AriaNeural", EdgeTts.ENGLISH_VOICE);
        assertTrue(EdgeTts.ssmlFor("Hello", EdgeTts.ENGLISH_VOICE, 100).contains("xml:lang='en-US'"));
    }
}
