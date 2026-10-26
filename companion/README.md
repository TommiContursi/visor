# Companion: Meta glasses → Android assistant launcher

A minimal standalone Android application, independent of Visor, using Meta Wearables Device Access Toolkit 1.0.1. It handles `Hey Meta, start Companion` and asks Android to open its configured default assistant (optionally falls back to the ChatGPT app). No model backend, subscription OAuth, accounts, camera, or microphone processing.

**Important:** Voice invocation only launches Companion by name. It does **not** replace bare "Hey Meta" or intercept regular Meta AI conversations. Even if Android opens ChatGPT, starting **ChatGPT Voice** automatically is not guaranteed by a public Android API. Turn on **ChatGPT → Settings → Voice → Start with Voice**, which starts Voice when ChatGPT opens into a new/empty conversation. Test phone unlocked **and** locked.

## Beta setup (user action)

1. Visit https://wearables.developer.meta.com/ and create a Wearables Android app in Developer Center. Choose exact app name **Companion**, package name **`fi.tomba.companion`**, callback scheme **`tombacompanion`**. Add the SHA-256 certificate fingerprint of the APK you will actually install, if Developer Center asks for it.
2. Set and save the app name/package **before** creating a version. Create a new version, assign it to a **Beta** release channel and invite/accept the **same Meta account** paired with your glasses.
3. Copy `local.properties.example` to `local.properties`, set the **Wearables Application ID and Client Token** from Developer Center. Never commit `local.properties`.
4. Open in Android Studio, sync Gradle, build/sign and install the APK. Configure app certificate/signature in the Developer Center for the build you install. `gradle :app:assembleDebug` builds a debug APK with Gradle 9.5 and the Android SDK installed (or use Android Studio Build > Make Project). For reliable Beta testing, use one stable signing certificate across updates.
5. In Meta AI, turn **Developer Mode OFF**, remove any previous Companion connection under **App connections**, then open Companion → **Yhdistä Meta AI -sovellukseen**. Confirm registration says `REGISTERED` and voice channel reaches `STARTED`.
6. Open ChatGPT once, set **Start with Voice** if available, select ChatGPT as Android's default digital assistant, and test **Testaa oletusavustajaa** from the phone first.
7. With glasses paired, say in English **"Hey Meta, start Companion"** (app name must match exactly). Check status and `adb logcat -s Companion` on failure. English US is the only confirmed spoken launch language as of Oct 2026.

Meta's Android voice invocation currently requires a Beta release channel rather than Developer Mode for real glasses. There is no separate per-app voice-invocation permission approval to request according to Meta's Oct 9 support response: https://github.com/facebook/meta-wearables-dat-android/discussions/209 . Voice invocations remain experimental and cannot be published to a production channel yet.

## Verification sequence

- Phone button **Testaa oletusavustajaa** should call Android `ACTION_ASSIST`. If Android does not resolve this action, Companion opens the installed ChatGPT app directly. Both paths are explicit test steps; neither claims Voice started.
- **Avaa ChatGPT -sovellus** is the control case: basic ChatGPT app activation without the assistant intent.
- Only then use the glasses command. Compare unlocked versus locked phone. If launcher works but Voice does not start, the blocker is ChatGPT's entry point, not Meta's invocation.
- Claude subscription access is best preserved by opening the official Claude app; this MVP deliberately implements only Android's default assistant + ChatGPT direct fallback. A Claude launch button can be added later if useful.

## Sources

- https://github.com/facebook/meta-wearables-dat-android
- https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/voice-invocations/SKILL.md
- https://github.com/facebook/meta-wearables-dat-android/discussions/209
- https://help.openai.com/en/articles/20001274-chatgpt-voice

## Technical boundaries

- Requires Android 10+; Meta AI app installed and the glasses paired there.
- SDK analytics/crash reporting opt out at the manifest level.
- No background service and no wake lock: voice launch must come from Meta AI/DAT. Android can restrict bringing ChatGPT to foreground from a locked screen; a failed lockscreen experiment does not mean the DAT launch failed.
- Requires a real Android SDK/toolchain to build. No device or end-to-end beta tests are included in this first source-code drop.

## Continuous build

The included GitHub Actions workflow compiles and uploads a debug APK for validation. GitHub-hosted debug APKs use ephemeral signing keys; for a Meta **Beta** channel, build/sign with a persistent key registered in Developer Center instead of expecting a CI debug APK to be accepted automatically. This source bundle does not include a Gradle wrapper JAR, so open it in Android Studio or use an installed Gradle 9.5.
