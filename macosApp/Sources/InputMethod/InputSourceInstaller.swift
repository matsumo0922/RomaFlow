import Carbon
import Foundation

/// TIS (Text Input Source Services) API を使って RomaFlow を入力ソースとして登録・有効化する。
/// `~/Library/Input Methods` へのコピーだけでは OS は次回ログインまで再スキャンしないため、
/// インストール時に本体バイナリへ CLI フラグを渡してこの処理を呼ぶ (Squirrel / Fcitx5 と同じパターン)。
enum InputSourceInstaller {
    static func run(command: String) -> Never {
        switch command {
        case "--register-input-source":
            register()

        case "--enable-input-source":
            enable()

        case "--select-input-source":
            select()

        default:
            NSLog("RomaFlow: unknown command: %@", command)
            exit(EXIT_FAILURE)
        }

        exit(EXIT_SUCCESS)
    }

    private static func register() {
        let bundleURL = Bundle.main.bundleURL
        let status = TISRegisterInputSource(bundleURL as CFURL)

        guard status == noErr else {
            NSLog("RomaFlow: TISRegisterInputSource failed (%d) for %@", status, bundleURL.path)
            exit(EXIT_FAILURE)
        }

        NSLog("RomaFlow: registered input source at %@", bundleURL.path)
    }

    private static func enable() {
        let inputSources = findOwnInputSources()

        guard !inputSources.isEmpty else {
            NSLog("RomaFlow: no input sources found. Run --register-input-source first.")
            exit(EXIT_FAILURE)
        }

        // input mode は親 input method が有効でないと有効化できないため、親 → mode の順に処理する
        let parentSourceID = Bundle.main.bundleIdentifier ?? "me.matsumo.inputmethod.RomaFlow"
        let parentSources = inputSources.filter { inputSourceID(of: $0) == parentSourceID }
        let modeSources = inputSources.filter { inputSourceID(of: $0) != parentSourceID }

        // macOS 12+ では isEnabled が true を返しても実際には一覧に出ていないことがあるため、
        // 状態を確認せず無条件で有効化する (McBopomofo の workaround と同じ)
        var failedSourceIDs: [String] = []

        for inputSource in parentSources + modeSources {
            let status = TISEnableInputSource(inputSource)
            NSLog("RomaFlow: TISEnableInputSource %@ -> %d", inputSourceID(of: inputSource), status)

            if status != noErr {
                failedSourceIDs.append(inputSourceID(of: inputSource))
            }
        }

        guard failedSourceIDs.isEmpty else {
            NSLog("RomaFlow: failed to enable input sources: %@", failedSourceIDs.joined(separator: ", "))
            exit(EXIT_FAILURE)
        }
    }

    private static func select() {
        let japaneseModeID = "me.matsumo.inputmethod.RomaFlow.Japanese"
        let japaneseMode = findOwnInputSources().first { inputSourceID(of: $0) == japaneseModeID }

        guard let japaneseMode else {
            NSLog("RomaFlow: Japanese input mode not found. Run --enable-input-source first.")
            exit(EXIT_FAILURE)
        }

        let status = TISSelectInputSource(japaneseMode)
        NSLog("RomaFlow: TISSelectInputSource %@ -> %d", japaneseModeID, status)
    }

    /// 親 input method と各 input mode を bundle ID で引き当てる (無効状態のものも含む)
    private static func findOwnInputSources() -> [TISInputSource] {
        let bundleIdentifier = Bundle.main.bundleIdentifier ?? "me.matsumo.inputmethod.RomaFlow"
        let filter = [kTISPropertyBundleID as String: bundleIdentifier] as CFDictionary

        guard let sourceList = TISCreateInputSourceList(filter, true)?.takeRetainedValue() else {
            return []
        }

        return sourceList as! [TISInputSource]
    }

    private static func inputSourceID(of inputSource: TISInputSource) -> String {
        guard let rawValue = TISGetInputSourceProperty(inputSource, kTISPropertyInputSourceID) else {
            return "(unknown)"
        }

        return Unmanaged<CFString>.fromOpaque(rawValue).takeUnretainedValue() as String
    }
}
