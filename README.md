# RomaFlow IME

RomaFlow IME is an experimental Japanese input method that lets users write in romaji first and convert the full text into natural Japanese afterward.

The project is a Kotlin Multiplatform codebase based on `matsumo0922/kmp-template`.

- App name: RomaFlow
- Android application ID: `me.matsumo.romaflow`
- Kotlin package name: `me.matsumo.romaflow`
- Android first, with platform-specific IME code added on top of the shared KMP modules
- macOS input method support planned next, with native host code calling shared Kotlin logic

## Verification

```sh
./gradlew detekt
./gradlew :androidApp:assembleDebug
```
