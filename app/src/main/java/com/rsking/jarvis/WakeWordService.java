package com.rsking.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/** Optional foreground speech-service wake phrase loop. Requires visible user opt-in. */
public class WakeWordService extends Service {
    public static final String ACTION_STOP = "com.rsking.jarvis.STOP_WAKE";
    private static final String CHANNEL = "jarvis_voice_service";
    private static final int NOTIFICATION_ID = 707;
    private SpeechRecognizer recognizer;
    private boolean stopping = false;
    private boolean restartScheduled = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Intent stop = new Intent(this, WakeWordService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS is listening")
                .setContentText("Say: Hello. Then give your command. Tap Stop to end listening.")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        beginListening();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    private void beginListening() {
        if (stopping) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return; }
        try {
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) { restart(); }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) handlePhrase(matches.get(0));
                    restart();
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speech.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speech.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speech.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Hey JARVIS");
            recognizer.startListening(speech);
        } catch (Exception e) { restart(); }
    }

    private void handlePhrase(String phrase) {
        String lower = phrase.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("hello jarvis");
        int len = 12;
        if (idx < 0) {
            // A standalone "hello" is the requested wake phrase.
            idx = lower.indexOf("hello");
            len = 5;
        }
        if (idx < 0) { idx = lower.indexOf("hey jarvis"); len = 10; }
        if (idx < 0) { idx = lower.indexOf("jarvis"); len = 6; }
        if (idx < 0 && lower.contains("جاروس")) { idx = lower.indexOf("جاروس"); len = 5; }
        if (idx < 0) return;
        String command = phrase.substring(Math.min(phrase.length(), idx + len)).trim();
        // Stop this recognizer before MainActivity starts its command recognizer.
        stopping = true;
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("wake_activated", true);
        if (!command.isEmpty()) open.putExtra("jarvis_command", command);
        startActivity(open);
        stopSelf();
    }

    private void restart() {
        if (stopping || restartScheduled) return;
        restartScheduled = true;
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) { } old.destroy(); }
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            restartScheduled = false;
            beginListening();
        }, 900);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "JARVIS voice listening", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent notification while the optional wake phrase listener is active");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        stopping = true;
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
