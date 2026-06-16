import AppKit
import Foundation
import InputMethodKit
import RomaFlowImeCore

@objc(RomaFlowInputController)
final class RomaFlowInputController: IMKInputController {
    private let engine = RomaFlowEngine()

    // 入力経路を handle(_:client:) に一本化するためのキーコード定数 (US 配列基準の物理キー番号)
    private let keyCodeReturn = 36
    private let keyCodeKeypadEnter = 76
    private let keyCodeEscape = 53
    private let keyCodeDelete = 51

    // insertText / setMarkedText で「置換範囲を指定しない」ことを示す range
    private let notFoundRange = NSRange(location: NSNotFound, length: 0)

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        super.init(server: server, delegate: delegate, client: inputClient)

        NSLog("RomaFlowInputController connected: %@", engine.smokeText())
    }

    // 入力処理はこのメソッドに集約する。処理したイベントでは super を呼ばず、二重更新を防ぐ。
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, event.type == .keyDown, let client = sender as? IMKTextInput else {
            return false
        }

        switch Int(event.keyCode) {
        case keyCodeReturn, keyCodeKeypadEnter:
            return commitComposition(with: client)
        case keyCodeEscape:
            return cancelComposition(with: client)
        case keyCodeDelete:
            return handleBackspace(with: client)
        default:
            break
        }

        // Cmd / Ctrl / Option を伴うキーはショートカット等なので IME では処理しない
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let hasCommandLikeModifier = !modifiers.intersection([.command, .control, .option]).isEmpty
        if hasCommandLikeModifier {
            return false
        }

        return handlePrintable(event, client: client)
    }

    override func setValue(_ value: Any!, forTag tag: Int, client sender: Any!) {
        super.setValue(value, forTag: tag, client: sender)

        guard let inputModeID = value as? String else {
            return
        }

        NSLog("RomaFlow input mode changed: %@", inputModeID)
    }

    // 印字可能な文字を engine に渡し、変換後のかなを未確定 (marked) テキストとして表示する
    private func handlePrintable(_ event: NSEvent, client: IMKTextInput) -> Bool {
        guard let characters = event.charactersIgnoringModifiers, !characters.isEmpty else {
            return false
        }

        let containsControlCharacters = characters.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        if containsControlCharacters {
            return false
        }

        let kana = engine.inputRomaji(text: characters)
        updateMarkedText(kana, client: client)

        return true
    }

    // Enter: 未確定中なら確定文字列を挿入し marked テキストを消す。未確定でなければアプリ側に流す。
    private func commitComposition(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let committed = engine.commit()
        client.insertText(committed, replacementRange: notFoundRange)
        clearMarkedText(client)

        return true
    }

    // Escape: 未確定中なら buffer を破棄し marked テキストを消す。未確定でなければアプリ側に流す。
    private func cancelComposition(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        engine.cancel()
        clearMarkedText(client)

        return true
    }

    // Backspace: 未確定中なら1文字戻して表示更新。未確定でなければアプリ側に流す。
    private func handleBackspace(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let kana = engine.deleteBackward()
        updateMarkedText(kana, client: client)

        return true
    }

    private func updateMarkedText(_ text: String, client: IMKTextInput) {
        if text.isEmpty {
            clearMarkedText(client)
            return
        }

        // カーソルは末尾に置く。length は NSString (UTF-16) 基準で数える。
        let cursorRange = NSRange(location: (text as NSString).length, length: 0)
        client.setMarkedText(text, selectionRange: cursorRange, replacementRange: notFoundRange)
    }

    private func clearMarkedText(_ client: IMKTextInput) {
        client.setMarkedText("", selectionRange: NSRange(location: 0, length: 0), replacementRange: notFoundRange)
    }
}