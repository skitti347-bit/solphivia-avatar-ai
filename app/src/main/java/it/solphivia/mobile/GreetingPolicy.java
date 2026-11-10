package it.solphivia.mobile;

final class GreetingPolicy {
    static final String OPENING="Hola! Sono Solphivia. Dimmi tutto: puoi aprire app, chiedere cose o semplicemente parlare. Per iniziare: Hola Solphivia.";
    private static long last=-10000L;
    static boolean claim() {
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-last<3000L)return false;
        last=now;return true;
    }
}
