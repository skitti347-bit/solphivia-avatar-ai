package it.solphivia.mobile;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import android.content.Intent;
import android.speech.RecognizerIntent;

/** Narrow, beginning-of-utterance aliases; never rewrite arbitrary dictated sentences. */
final class VoicePhrases {
    static String normalized(String text) {
        return Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ITALIAN),Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
    static String wakeRequest(String text) {
        String value=normalized(text);
        if(value.equals("o la sua figlia") || value.equals("ola sua figlia") || value.equals("hola sua figlia"))return "";
        // Italian ASR renders "Hola Solphivia" many ways ("ho la solphivia", "o la sul fibbia", ...).
        java.util.regex.Matcher m=java.util.regex.Pattern.compile(
                "^(hola|ola|ho la|o la|oh la)[ ]*(sol|so|sul)?[ ]*(phivia|fivia|livia|ivia|via|fia|fibbia|pibia|bia)((?:[ ].*)?)$").matcher(value);
        if(m.matches())return m.group(4)==null?"":m.group(4).trim();
        m=java.util.regex.Pattern.compile("^(ciao|ehi|hey)[ ]*(solphivia|solfivia|silvia|sofia|solvia)((?:[ ].*)?)$").matcher(value);
        if(m.matches())return m.group(3)==null?"":m.group(3).trim();
        return null;
    }
    static boolean greeting(String text) {
        String wake=wakeRequest(text);return wake!=null && wake.isEmpty();
    }
    static String best(List<String> matches) {
        if(matches==null || matches.isEmpty())return "";
        if(normalized(matches.get(0)).split(" ").length<=6)for(String match:matches)if(greeting(match))return "Hola Solphivia";
        return matches.get(0)==null?"":matches.get(0).trim();
    }
    static Intent recognitionIntent() {
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"it-IT")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
        if(android.os.Build.VERSION.SDK_INT>=33)intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                new java.util.ArrayList<>(java.util.Arrays.asList("Solphivia","Hola Solphivia","Ciao Solphivia","Meteorina")));
        return intent;
    }
}
