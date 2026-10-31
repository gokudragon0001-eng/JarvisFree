package com.rsking.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.media.AudioManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_AUDIO = 41;
    private static final int REQ_CONTACTS = 42;
    private String pendingContactName = "";
    private String pendingMessageText = "";
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private TextView status, heard, response;
    private EditText prompt;
    private Button mic, ask, keyButton, accessibilityButton, wakeButton;
    private boolean pendingWakeStart = false;
    private boolean listeningAfterWake = false;
    private SharedPreferences prefs;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final JSONArray conversation = new JSONArray();
    private static final String MODEL = "gemini-3.5-flash-lite";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("jarvis_settings", MODE_PRIVATE);
        status = findViewById(R.id.statusText);
        heard = findViewById(R.id.heardText);
        response = findViewById(R.id.responseText);
        prompt = findViewById(R.id.promptInput);
        mic = findViewById(R.id.micButton);
        ask = findViewById(R.id.askButton);
        keyButton = findViewById(R.id.keyButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        wakeButton = findViewById(R.id.wakeButton);
        tts = new TextToSpeech(this, this);
        mic.setOnClickListener(v -> startListening());
        ask.setOnClickListener(v -> submitText());
        keyButton.setOnClickListener(v -> showKeyDialog());
        accessibilityButton.setOnClickListener(v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { respond("Could not open Accessibility settings."); }
        });
        wakeButton.setOnClickListener(v -> startWakeWordService());
        status.setText(hasKey() ? "READY • AI connected when online" : "READY • Add your free Gemini API key to enable AI");
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Speech recognition service unavailable. Text chat can still work.");
            mic.setEnabled(false);
        }
        // First launch asks for microphone permission; after approval, start the opted-in
        // persistent wake listener. Android displays its ongoing notification while active.
        boolean launchedByWake = getIntent() != null && getIntent().getBooleanExtra("wake_activated", false);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!launchedByWake) startWakeWordService();
        } else {
            pendingWakeStart = !launchedByWake;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        handleIncomingIntent(getIntent());
    }

    @Override public void onInit(int result) {
        if (result == TextToSpeech.SUCCESS) {
            int lang = tts.setLanguage(new Locale("en", "US"));
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED)
                tts.setLanguage(Locale.getDefault());
        }
    }

    private boolean hasKey() { return !prefs.getString("api_key", "").trim().isEmpty(); }

    private void showKeyDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Paste Gemini API key");
        input.setText(prefs.getString("api_key", ""));
        new AlertDialog.Builder(this).setTitle("Free AI setup")
            .setMessage("Create a key in Google AI Studio, then paste it here. Free-tier limits and eligibility depend on Google.")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String key = input.getText().toString().trim();
                prefs.edit().putString("api_key", key).apply();
                status.setText(key.isEmpty() ? "AI key not set" : "AI key saved • Internet required");
            })
            .setNeutralButton("Get free key", (d, w) -> openUrl("https://aistudio.google.com/apikey"))
            .setNegativeButton("Cancel", null).show();
    }

    private void startWakeWordService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWakeStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        try {
            Intent service = new Intent(this, WakeWordService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            respond("Hello wake word is active. Say Hello, then give your command. Keep the notification visible; listening uses battery and may use internet speech recognition.");
        } catch (Exception e) { respond("Could not start wake phrase listener. Open JARVIS again and try."); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("wake_activated", false)) listeningAfterWake = true;
        if (intent.hasExtra("jarvis_command")) {
            String command = intent.getStringExtra("jarvis_command");
            if (command != null && !command.trim().isEmpty()) {
                heard.setText(command);
                prompt.setText(command);
                processInput(command);
                if (intent.getBooleanExtra("wake_activated", false)) {
                    new android.os.Handler(getMainLooper()).postDelayed(this::startWakeWordService, 2500);
                }
            }
        } else if (intent.getBooleanExtra("wake_activated", false)) {
            listeningAfterWake = true;
            respond("Ji, main sun raha hoon. Batayein?");
            new android.os.Handler(getMainLooper()).postDelayed(this::startListening, 700);
        }
        intent.removeExtra("wake_activated");
        intent.removeExtra("jarvis_command");
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO); return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { status.setText("LISTENING… Speak now"); }
            public void onBeginningOfSpeech() { }
            public void onRmsChanged(float v) { }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { status.setText("Processing command…"); }
            public void onError(int e) { status.setText("Could not hear that. Tap and try again."); }
            public void onResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String command = matches.get(0);
                    heard.setText(command);
                    prompt.setText(command);
                    processInput(command);
                    if (listeningAfterWake) {
                        listeningAfterWake = false;
                        new android.os.Handler(getMainLooper()).postDelayed(() -> startWakeWordService(), 1800);
                    }
                }
            }
            public void onPartialResults(Bundle b) { }
            public void onEvent(int t, Bundle b) { }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS");
        recognizer.startListening(intent);
    }

    private void submitText() {
        String text = prompt.getText().toString().trim();
        if (text.isEmpty()) return;
        heard.setText(text);
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        processInput(text);
    }

    private void processInput(String raw) {
        String c = raw.toLowerCase(Locale.ROOT).trim();
        // Try a dynamically discovered installed app name after the common app shortcuts.
        if (c.startsWith("open ") || c.startsWith(" kholo ") || c.endsWith(" kholo")) {
            String requested = c.replaceFirst("(?i)^open\\s+", "").replaceFirst("(?i)\\s+kholo$", "").trim();
            if (openInstalledAppByName(requested)) return;
        }
        // Local device actions run on-device and do not need an AI key.
        if (containsAny(c, "open whatsapp", "whatsapp kholo", "whatsapp کھولو", "whatsapp kholo")) { openPackage("com.whatsapp", "WhatsApp"); return; }
        if (containsAny(c, "open instagram", "instagram kholo", "instagram کھولو")) { openPackage("com.instagram.android", "Instagram"); return; }
        if (containsAny(c, "open youtube", "youtube kholo", "youtube کھولو")) { openPackage("com.google.android.youtube", "YouTube"); return; }
        if (containsAny(c, "open facebook", "facebook kholo", "facebook کھولو")) { openPackage("com.facebook.katana", "Facebook"); return; }
        if (containsAny(c, "open chrome", "chrome kholo", "browser kholo")) { openPackage("com.android.chrome", "Chrome"); return; }
        if (containsAny(c, "open maps", "google maps kholo", "maps kholo")) { openPackage("com.google.android.apps.maps", "Google Maps"); return; }
        if (containsAny(c, "open gmail", "gmail kholo")) { openPackage("com.google.android.gm", "Gmail"); return; }
        if (containsAny(c, "open spotify", "spotify kholo")) { openPackage("com.spotify.music", "Spotify"); return; }
        if (containsAny(c, "open telegram", "telegram kholo")) { openPackage("org.telegram.messenger", "Telegram"); return; }
        if (containsAny(c, "open snapchat", "snapchat kholo")) { openPackage("com.snapchat.android", "Snapchat"); return; }
        if (containsAny(c, "open tiktok", "tiktok kholo")) { openPackage("com.zhiliaoapp.musically", "TikTok"); return; }
        if (containsAny(c, "open contacts", "contacts kholo")) { launch(new Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI), "Opening contacts"); return; }
        if (containsAny(c, "open phone", "dialer kholo", "open dialer")) { launch(new Intent(Intent.ACTION_DIAL), "Opening phone dialer"); return; }
        if (containsAny(c, "open camera", "camera kholo", "camera کھولو")) { launch(new Intent("android.media.action.IMAGE_CAPTURE"), "Opening camera"); return; }
        if (containsAny(c, "open settings", "settings kholo", "settings کھولو")) { launch(new Intent(android.provider.Settings.ACTION_SETTINGS), "Opening settings"); return; }
        if (containsAny(c, "wifi settings", "open wifi", "wifi kholo", "wi-fi settings")) { launch(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings"); return; }
        if (containsAny(c, "bluetooth settings", "open bluetooth", "bluetooth kholo")) { launch(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings"); return; }
        if (containsAny(c, "volume up", "volume barhao", "awaz barhao", "volume increase")) { changeVolume(AudioManager.ADJUST_RAISE); return; }
        if (containsAny(c, "volume down", "volume kam", "awaz kam", "volume decrease")) { changeVolume(AudioManager.ADJUST_LOWER); return; }
        if (c.startsWith("type ") || c.startsWith("likho ") || c.startsWith("text likho ")) {
            String text = raw.replaceFirst("(?i)^\\s*(type|likho|text likho)\\s*", "").trim();
            if (text.isEmpty()) { respond("Please say the text after the word type."); return; }
            boolean typed = JarvisAccessibilityService.typeIntoFocusedField(this, text);
            respond(typed ? "Text entered in the focused field. Please review it before sending." : "Enable JARVIS Phone Control in Accessibility settings, open a text field, then try again.");
            return;
        }
        if (c.startsWith("youtube search ") || c.startsWith("search youtube for ") || c.startsWith("youtube par ") && c.endsWith(" search karo")) {
            String q = raw.replaceFirst("(?i)^\\s*(youtube search|search youtube for|youtube par)\\s*", "").replaceFirst("(?i)\\s*search karo$", "").trim();
            if (!q.isEmpty()) { openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q)); respond("YouTube par " + q + " search kar raha hoon."); return; }
        }
        if (c.startsWith("open website ") || c.startsWith("website kholo ") || c.startsWith("open site ")) {
            String address = raw.replaceFirst("(?i)^\\s*(open website|website kholo|open site)\\s*", "").trim();
            if (!address.isEmpty()) { if (!address.matches("(?i)^https?://.*")) address = "https://" + address; openUrl(address); respond("Website khol raha hoon: " + address); return; }
        }
        if (c.startsWith("search ") || c.startsWith("google ") || c.contains("search for")) {
            String q = raw.replaceFirst("(?i)^\\s*(search( for)?|google)\\s*", "").trim();
            openUrl("https://www.google.com/search?q=" + Uri.encode(q)); respond("Searching Google for " + q); return;
        }
        if (c.startsWith("whatsapp message to ") || c.startsWith("message whatsapp ") || c.startsWith("whatsapp ko message ")) {
            String rest = raw.replaceFirst("(?i)^\\s*(whatsapp message to|message whatsapp|whatsapp ko message)\\s*", "").trim();
            int split = rest.indexOf(':');
            if (split < 1 || split == rest.length() - 1) { respond("Is tarah bolain: WhatsApp message to Irfan: Hello Irfan"); return; }
            openWhatsAppContact(rest.substring(0, split).trim(), rest.substring(split + 1).trim());
            return;
        }
        if (containsAny(c, "send whatsapp", "whatsapp message", "message on whatsapp")) {
            Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, raw); share.setPackage("com.whatsapp");
            try { startActivity(share); respond("WhatsApp opened. Choose the contact, review the message, then press Send yourself."); }
            catch (Exception e) { respond("WhatsApp is not installed."); }
            return;
        }
        askGemini(raw);
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private void askGemini(String question) {
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) { respond("AI setup is not complete. Tap the API KEY button and add your free Gemini key."); return; }
        status.setText("JARVIS AI is thinking…");
        ask.setEnabled(false); mic.setEnabled(false);
        network.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
                conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("x-goog-api-key", key);
                JSONObject userPart = new JSONObject().put("text", question);
                JSONArray contents;
                synchronized (conversation) {
                    conversation.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(userPart)));
                    while (conversation.length() > 16) conversation.remove(0);
                    contents = new JSONArray(conversation.toString());
                }
                JSONObject system = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                    "You are JARVIS, a concise and helpful phone assistant. Reply in the same language as the user, including Urdu or Roman Urdu. You may explain how to use phone features, but never claim to have performed a device action unless the app actually did it. For actions not supported by the app, clearly say so.")));
                JSONObject body = new JSONObject().put("systemInstruction", system).put("contents", contents);
                try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) result.append(line);
                if (code < 200 || code >= 300) throw new Exception("Gemini API error " + code + ": " + result);
                JSONObject json = new JSONObject(result.toString());
                String answer = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
                synchronized (conversation) {
                    conversation.put(new JSONObject().put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", answer))));
                    while (conversation.length() > 16) conversation.remove(0);
                }
                runOnUiThread(() -> respond(answer));
            } catch (Exception ex) {
                synchronized (conversation) {
                    int last = conversation.length() - 1;
                    if (last >= 0 && "user".equals(conversation.optJSONObject(last).optString("role"))) conversation.remove(last);
                }
                String err = ex.getMessage() == null ? "Connection failed" : ex.getMessage();
                runOnUiThread(() -> respond("AI connection issue: " + err + ". Check internet, API key and free-tier availability."));
            } finally {
                if (conn != null) conn.disconnect();
                runOnUiThread(() -> { ask.setEnabled(true); mic.setEnabled(true); });
            }
        });
    }

    private void changeVolume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) { respond("Volume control is unavailable."); return; }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        respond(direction == AudioManager.ADJUST_RAISE ? "Volume increased." : "Volume decreased.");
    }

    private boolean openInstalledAppByName(String requested) {
        if (requested.isEmpty()) return false;
        String wanted = requested.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "").trim();
        android.content.Intent query = new android.content.Intent(android.content.Intent.ACTION_MAIN, null);
        query.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        android.content.pm.ResolveInfo best = null; int bestScore = 0;
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
            CharSequence label = info.loadLabel(getPackageManager());
            if (label == null) continue;
            String candidate = label.toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            int score = candidate.equals(wanted) ? 100 : (candidate.startsWith(wanted) || wanted.startsWith(candidate) ? 70 : (candidate.contains(wanted) ? 50 : 0));
            if (score > bestScore) { best = info; bestScore = score; }
        }
        if (best != null && bestScore >= 50) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launchIntent != null) { launch(launchIntent, "Opening " + best.loadLabel(getPackageManager())); return true; }
        }
        return false;
    }

    private void openWhatsAppContact(String name, String message) {
        if (name.isEmpty() || message.isEmpty()) { respond("Contact name aur message dono batayein."); return; }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactName = name; pendingMessageText = message;
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS); return;
        }
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + name + "%"}, null);
            if (cursor == null || !cursor.moveToFirst()) { respond("Contacts mein " + name + " nahi mila. Contact save karein ya WhatsApp manually kholein."); return; }
            String foundName = cursor.getString(0); String phone = cursor.getString(1).replaceAll("[^0-9+]", "");
            phone = phone.replaceAll("^\\+", "");
            if (phone.startsWith("0")) phone = "92" + phone.substring(1);
            if (phone.isEmpty()) { respond("Is contact ka valid phone number nahi mila."); return; }
            Uri uri = Uri.parse("https://api.whatsapp.com/send?phone=" + Uri.encode(phone) + "&text=" + Uri.encode(message));
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
            respond(foundName + " ka WhatsApp chat khol raha hoon. Message check karke Send khud press karein.");
        } catch (Exception e) { respond("WhatsApp chat open nahi ho saka. WhatsApp installed aur contact number sahi hona chahiye."); }
        finally { if (cursor != null) cursor.close(); }
    }
    private void openPackage(String pkg, String label) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { respond(label + " is not installed."); return; }
        launch(i, "Opening " + label);
    }
    private void launch(Intent i, String message) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); respond(message); }
        catch (Exception ex) { respond("I could not perform that action."); }
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show(); }
    }
    private void respond(String text) {
        response.setText(text);
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply");
        status.setText("READY • Speak or type another command");
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingWakeStart) { pendingWakeStart = false; startWakeWordService(); }
            else startListening();
        } else if (request == REQ_AUDIO) { pendingWakeStart = false; Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show(); }
        else if (request == REQ_CONTACTS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                String n = pendingContactName, m = pendingMessageText; pendingContactName = ""; pendingMessageText = ""; openWhatsAppContact(n, m);
            } else respond("Contact search ke liye Contacts permission zaroori hai. Aap WhatsApp manually khol sakte hain.");
        }
    }
    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        network.shutdownNow(); super.onDestroy();
    }
}
