# J.A.R.V.I.S — Android voice assistant (source project v1.2)

This is a native Java Android Studio project intended for Android 11 (including OPPO A16). It is a **source project, not a prebuilt or device-tested APK**. It requires Android Studio, JDK 17 and Android SDK Platform 35 to build.

## Included in this version

- Dark JARVIS-style screen with microphone button and text chat.
- Android SpeechRecognizer input and Android TextToSpeech replies. Speech language support depends on the speech service installed on the phone.
- Gemini Developer API chat using the stable model ID `gemini-3.5-flash-lite`. Google lists this model in its current Gemini API model catalogue.
- In-app API-key setup shortcut and local private-preferences storage.
- Local voice commands to open WhatsApp, Instagram, YouTube, Facebook, Chrome, Maps, Gmail, Spotify, Telegram, Snapchat, TikTok, Camera, Settings, Contacts and Phone/Dialer (only if installed).
- Google search, Wi-Fi/Bluetooth settings screens, and media volume up/down. Android opens protected settings pages; it does not silently toggle protected system controls.
- Optional foreground wake-phrase listener. User must start it from the app and can stop it from its persistent notification. Say `Hey JARVIS, open WhatsApp`. This uses Android's speech recognition service (often network-based), consumes battery, and is not a low-power offline hotword engine. Background reliability varies by phone vendor; OPPO battery management may stop it.
- Optional Accessibility Service. User must explicitly enable **JARVIS Phone Control** in Android Accessibility settings. It only enters dictated text into the currently focused editable field; it does not press Send, click arbitrary buttons, or send messages.
- WhatsApp share intent can pass text into WhatsApp; user must choose a recipient and press Send. Contact-name resolution and automated sending are not implemented.

## Setup the free AI

1. Open https://aistudio.google.com/apikey and create a Gemini API key in a project eligible for the Gemini Developer API free tier.
2. Install the app, tap **SET UP FREE AI KEY**, paste the key and save.
3. Internet is required for Gemini chat. Google's free tier has model-specific quotas and eligibility; it can change. Google says free-tier content may be used to improve its products, so do not send passwords, private messages, financial details or other sensitive information. See the official [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing), [model list](https://ai.google.dev/gemini-api/docs/models) and [billing tiers](https://ai.google.dev/gemini-api/docs/billing).
4. The key is stored in this app's private SharedPreferences. That is acceptable for a personal test installation, but **not secure for public distribution**: a determined user can extract a key from an APK/device. A production app should call a backend that protects the key and applies per-user quotas. Never publish an APK containing your personal API key.

## Build a debug APK

1. Install Android Studio (current stable), JDK 17 and Android SDK Platform 35 / Build Tools from SDK Manager.
2. Open the `JarvisFree` folder in Android Studio as an existing Gradle project. If Android Studio asks to create/sync Gradle wrapper files, accept its compatible Gradle 8.9+ wrapper setup (Android Gradle Plugin 8.7.3).
3. Wait for Gradle sync, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Android Studio normally writes the debug APK to `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy the APK to the OPPO A16, allow installation from that source if Android asks, and install.

## OPPO A16 first-run setup

- Grant Microphone permission when prompted.
- For AI chat, add your own Gemini API key.
- For voice typing inside another app, open JARVIS > **ENABLE OPTIONAL PHONE CONTROL** and enable **JARVIS Phone Control**. This is a powerful Android permission; enable it only because you trust this app.
- For wake phrase, tap **START HEY JARVIS LISTENING**. Keep its ongoing notification visible. To stop it, tap **Stop** in the notification. If OPPO battery management stops the service, allow background activity / disable battery optimization for JARVIS if you choose.

## Important limitations

- No Android SDK, Gradle executable or physical OPPO A16 was available in the build environment used to prepare this source. Therefore the project has **not been compiled, installed or tested on a phone here**. Do not treat it as a verified APK.
- Wake phrase recognition is an experimental foreground speech-recognition loop, not a dedicated always-on offline wake-word engine. It needs microphone permission, internet/service support in many configurations, a persistent notification and battery.
- Gemini AI answers questions; it does not itself get unrestricted phone-control permissions. Device actions are a limited local command set.
- WhatsApp contact lookup, Instagram DM automation, silent sending, arbitrary app control, Bluetooth/Wi-Fi silent toggles, and protected system actions are not included. Android intentionally restricts many of these.
- Free Gemini API quotas and model access can vary by account, region and time.
