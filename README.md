# RomaFlow IME

RomaFlow IME is an experimental Japanese input method that lets users keep typing in romaji while the composing text is live-converted into natural Japanese.

The project is a Kotlin Multiplatform codebase based on `matsumo0922/kmp-template`.

- App name: RomaFlow
- Android application ID: `me.matsumo.romaflow`
- Kotlin package name: `me.matsumo.romaflow`
- macOS first, with an InputMethodKit host calling shared Kotlin IME logic
- Xcode projects are generated with XcodeGen
- Kotlin Multiplatform output for Apple hosts uses Swift Export
- Android InputMethodService support is planned after the shared live-conversion core is validated on macOS

## Verification

```sh
./gradlew detekt
./gradlew :androidApp:assembleDebug
```

## macOS Bootstrap Checks

```sh
xcodegen --version
./gradlew :shared:tasks --all
```
