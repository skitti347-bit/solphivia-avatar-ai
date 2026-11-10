# Solphivia IA — prototipo per Aktamarcus (fork GitHub)

Prototipo non ufficiale costruito su `solphivia-avatar-ai` 1.1.0-beta.5.
Codice MIT come il progetto; nessun asset grafico modificato (solo colori UI).

## Cosa c'è in questa consegna

- `source_code.zip` — L'INTERO progetto modificato, pronto da caricare su GitHub.
  Al suo interno (root) trovi anche `PROTOTIPO-IT.md` (questo documento) e
  `prototipo.diff` (le differenze esatte rispetto ai sorgenti 1.1.0-beta.5, 13 file).
- `solphivia-v8.3-debug.apk` — build debug installabile, già provata su Redmi 15
  (HyperOS/Android 16). Non serve: `adb install -r <apk>`.

## Come caricarlo su GitHub (flusso "fork", spiegato semplice)

1. Sul tuo GitHub apri il progetto originale `Trikto7e8/solphivia-avatar-ai`
   e premi **Fork**: crea una copia del repo sul tuo account. La copia è tua,
   puoi modificarla liberamente; GitHub ricorda da quale progetto viene.
2. Per caricare `source_code.zip`: dentro il TUO fork premi **Add file →
   Upload existing files**, trascina il contenuto estratto dello zip (non lo zip,
   i file dentro), scrivi un messaggio tipo "Prototipo v8.3" e premi **Commit**.
3. Se un giorno vuoi proporre le tue modifiche al progetto originale, dal fork
   premi **Contribute → Open pull request**: GitHub mostra le tue modifiche
   all'autore originale, che può accettarle. Finché non lo fai, nulla tocca
   il progetto originale.
4. Nota: gli asset vocale/animazione pesano ~115 MB in totale (nessun file
   singolo supera i 100 MB, quindi GitHub li accetta, ma il push impiega un po').

## Stato attuale (v8.3, 10 nov 2026)

Testato sul Redmi 15 reale. 101/101 test JVM verdi.

1. **Onboarding cyber obbligatorio**: scelta provider (ChatGPT / OpenRouter /
   OpenAI compatibile / LLM locale) con test di connessione reale, autocompletamento
   modelli (`GET /v1/models`), permessi mic+overlay dentro il flusso.
2. **Provider custom**: chiave API cifrata AES-256-GCM in AndroidKeyStore;
   streaming `chat/completions` con lo stesso prompt di personalità e lo stesso
   parser dei tag `[[SOLPHIVIA:...]]` (animazioni e movimenti identici su
   qualunque provider). LLM locale via HTTP su LAN (LM Studio/Ollama).
3. **Voce di Solphivia = Edge TTS** (voce femminile it-IT-ElsaNeural, fallback
   en-US-AriaNeural): WebSocket RFC6455 a mano, token DRM Sec-MS-GEC v2
   (CS64-SHA256) verificato contro edge-tts 7.3.1. Niente più TTS Android.
4. **Voce che salta → riaggancio**: se la sintesi fallisce a metà riga, la riga
   NON si perde ( resta in pausa). Nel menu ☰: «Ripeti l'ultima frase» (risente
   da capo) e «Riattiva la voce» (riprende da dov'era). 97→101 test a copertura.
5. **Barge-in istantaneo**: mentre parla, se senti tu la voce (~300 ms sostenuti,
   VAD adattivo con AEC) si muta SUBITO senza aspettare comandi, e ti riascolta.
   Quello che dici mentre lei parla finisce in bozza nel campo testo.
6. **Menu ☰ (6 voci)**: Memoria, Prova animazioni, Apri Chat/Live Avatar,
   Silenzia/Riattiva la voce (interruttore ON/OFF del TTS Edge), Ripeti
   l'ultima frase, Cambia modello. Dialog restiliti cyber (CyberDialogs).
7. **Schede che si aprono da overlay su HyperOS**: avvii via PendingIntent con
   creator background-start mode (Android 16 blocca/crashava con gli startActivity
   semplici). «Apri Chat» resta aperta (extra `stay_open`), «Cambia modello»
   apre l'onboarding precompilato (extra `change_model`).
8. **Trascrizione tornata buona**: eliminato il passaggio automatico al
   riconoscimento locale Vosk dopo ogni risposta (era lui che "indovinava" le
   parole); Vosk resta solo per wake-word e interrupt, i comandi vanno a Google.
   «Hola Solphivia» riconosciuto anche se Google lo scrive «Ho la Solphivia».

## Limiti noti (da chiudere insieme)

- Allegati solo sul percorso ChatGPT (i custom rispondono in testo)
- La memoria personale va in chiaro al provider scelto: da riconsentire
  con il flusso MemoryConsent per i provider cloud
- `usesCleartextTraffic="true"` globale: da restringere alla LAN privata
- I dialog di MemoryActivity e due di MainActivity sono ancora in stile sistema
- L'overlay sotto swipe-away aggressivo di HyperOS: verificare sopravvivenza
  (restrizioni batteria)
- Il barge-in richiede la sessione microfono attiva (notifica «Microfono
  Solphivia attivo»); taratura soglie sul campo se needed (voice_health:
  barge_rms_peak, barge_interruptions)

## Cosa NON è toccato

- Asset grafici e personaggio (diritti riservati): solo colori di UI
- La logica OAuth ChatGPT esistente e i suoi test

## Comandi di build (per chi riceve il sorgente)

```
# JDK 17 + Android SDK (platforms;android-35, build-tools;35.0.0)
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # 101 test
```
