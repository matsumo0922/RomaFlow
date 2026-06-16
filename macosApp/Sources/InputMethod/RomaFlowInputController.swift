import AppKit
import Foundation
import InputMethodKit
import RomaFlowImeCore

@objc(RomaFlowInputController)
final class RomaFlowInputController: IMKInputController {
    private let engine = RomaFlowEngine()

    // 縦一列の変換候補ウィンドウ。Tab 変換後に複数候補があるときだけ表示する。
    private let candidateWindow: IMKCandidates

    // 入力経路を handle(_:client:) に一本化するためのキーコード定数 (US 配列基準の物理キー番号)
    private let keyCodeReturn = 36

    private let keyCodeKeypadEnter = 76
    private let keyCodeEscape = 53
    private let keyCodeDelete = 51
    private let keyCodeTab = 48
    private let keyCodeArrowLeft = 123
    private let keyCodeArrowRight = 124
    private let keyCodeArrowDown = 125
    private let keyCodeArrowUp = 126

    // insertText / setMarkedText で「置換範囲を指定しない」ことを示す range
    private let notFoundRange = NSRange(location: NSNotFound, length: 0)

    // 候補ウィンドウを表示中かどうか。表示中はキー入力を候補ウィンドウ操作へ振り分ける。
    private var isCandidateWindowVisible = false

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        candidateWindow = IMKCandidates(server: server, panelType: kIMKSingleColumnScrollingCandidatePanel)
        super.init(server: server, delegate: delegate, client: inputClient)

        NSLog("RomaFlowInputController connected: %@", engine.smokeText())
    }

    // 入力処理はこのメソッドに集約する。処理したイベントでは super を呼ばず、二重更新を防ぐ。
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, event.type == .keyDown, let client = sender as? IMKTextInput else {
            return false
        }

        // 候補ウィンドウ表示中は、まず候補操作として処理する。
        if isCandidateWindowVisible {
            return handleCandidateWindowEvent(event, client: client)
        }

        switch Int(event.keyCode) {
        case keyCodeReturn, keyCodeKeypadEnter:
            return performCommit(with: client)
        case keyCodeEscape:
            return cancelComposition(with: client)
        case keyCodeDelete:
            return handleBackspace(with: client)
        case keyCodeTab:
            return handleConvert(event, client: client)
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

    // IMK が composition の即時終了を要求したとき (フォーカス喪失や composition 外クリック等) に呼ばれる。
    // Enter 以外の終了経路でも同じ commit/clear 経路へ流し、未確定テキストと engine buffer を残さない。
    override func commitComposition(_ sender: Any!) {
        guard let client = sender as? IMKTextInput else {
            return
        }

        _ = performCommit(with: client)
    }

    // 候補ウィンドウへ表示する候補を返す。IMKCandidates.update() から呼ばれる。
    // Swift Export 越しに List を出せないため、engine からは改行区切りの String で受け取り分割する。
    override func candidates(_ sender: Any!) -> [Any] {
        let joined = engine.candidatesText()

        guard !joined.isEmpty else {
            return []
        }

        return joined.components(separatedBy: "\n")
    }

    // 候補ウィンドウで候補が選択された (Enter / クリック / 数字キー) ときに呼ばれる。
    override func candidateSelected(_ candidateString: NSAttributedString!) {
        let selected = candidateString?.string ?? ""

        guard !selected.isEmpty, let client = client() as? IMKTextInput else {
            return
        }

        // IMKCandidates の event 処理中に呼ばれるため、再入を避けて main queue に積む (reference 5.4)
        DispatchQueue.main.async {
            let committed = self.engine.commitCandidate(text: selected)
            client.insertText(committed, replacementRange: self.notFoundRange)
            self.clearMarkedText(client)
            self.hideCandidateWindow()
        }
    }

    // 印字可能な文字を engine に渡し、変換後のかなを未確定 (marked) テキストとして表示する
    private func handlePrintable(_ event: NSEvent, client: IMKTextInput) -> Bool {
        // Shift を反映した実際の入力文字が必要なので characters を使う (charactersIgnoringModifiers だと
        // Shift+A が "a" になり大文字を入力できない)。Cmd / Ctrl / Option は handle 側で弾いている。
        guard let characters = event.characters, !characters.isEmpty else {
            return false
        }

        let containsControlCharacters = characters.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        if containsControlCharacters {
            return false
        }

        // 変換済み状態での追加入力は、表示中の変換結果を WYSIWYG で確定してから新しい入力を始める
        if engine.isConverted() {
            let committed = engine.commit()
            client.insertText(committed, replacementRange: notFoundRange)
        }

        // 未入力状態の space はアプリにそのまま空白を入れさせる (空の marked text を出さない)
        if characters == " ", !engine.hasComposition() {
            return false
        }

        let kana = engine.inputRomaji(text: characters)
        updateMarkedText(kana, client: client)

        return true
    }

    // Tab: 未確定かなを ConversionProvider で変換し、結果を marked テキストとして表示する。
    // 複数候補があれば候補ウィンドウも表示する。
    // 変換するのは修飾キーなしの Tab だけ。Cmd+Tab / Ctrl+Tab / Option+Tab / Shift+Tab などは
    // アプリ側のショートカットなので、未確定中なら WYSIWYG で確定してから false を返して流す。
    // 未確定でない素の Tab も false を返し、通常の Tab としてアプリ側に流す。
    private func handleConvert(_ event: NSEvent, client: IMKTextInput) -> Bool {
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let hasShortcutModifier = !modifiers.intersection([.command, .control, .option, .shift]).isEmpty
        if hasShortcutModifier {
            _ = performCommit(with: client)

            return false
        }

        guard engine.hasComposition() else {
            return false
        }

        let converted = engine.convert()
        updateMarkedText(converted, client: client)

        if engine.hasMultipleCandidates() {
            showCandidateWindow()
        }

        return true
    }

    // 候補ウィンドウ表示中のキー処理。↑↓ で候補移動、Enter で選択し、それ以外は変換結果を確定してから処理する。
    private func handleCandidateWindowEvent(_ event: NSEvent, client: IMKTextInput) -> Bool {
        switch Int(event.keyCode) {
        case keyCodeArrowUp, keyCodeArrowDown, keyCodeArrowLeft, keyCodeArrowRight, keyCodeReturn, keyCodeKeypadEnter:
            // navigation と確定キーは候補ウィンドウへ転送する。確定時は candidateSelected(_:) が呼ばれる。
            candidateWindow.interpretKeyEvents([event])

            return true
        case keyCodeEscape:
            // 候補ウィンドウだけ閉じる。変換結果は marked のまま残し、再度の Escape で全体を取り消す。
            hideCandidateWindow()

            return true
        case keyCodeDelete:
            // 変換を取り消してかな入力へ戻す。
            hideCandidateWindow()
            let kana = engine.deleteBackward()
            updateMarkedText(kana, client: client)

            return true
        default:
            return commitFromCandidateWindow(event, client: client)
        }
    }

    // 候補ウィンドウ表示中に navigation 以外のキーが来たとき、表示中の変換結果を確定してからそのキーを処理する。
    private func commitFromCandidateWindow(_ event: NSEvent, client: IMKTextInput) -> Bool {
        hideCandidateWindow()

        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let hasCommandLikeModifier = !modifiers.intersection([.command, .control, .option]).isEmpty
        let characters = event.characters ?? ""
        let isControlInput = characters.isEmpty || characters.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)

        // 印字文字なら表示中の変換結果を確定して新しい composition を開始する (handlePrintable が確定を担う)
        if !hasCommandLikeModifier, !isControlInput {
            return handlePrintable(event, client: client)
        }

        // Tab やショートカット等は変換結果を確定し、元のキーはアプリ側へ流す
        _ = performCommit(with: client)

        return false
    }

    // 未確定中なら確定文字列を挿入し marked テキストを消す。未確定でなければ false を返してアプリ側に流す。
    // Enter と involuntary commit (commitComposition) の確定経路がこのメソッドを共有する。
    private func performCommit(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        hideCandidateWindow()
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

        hideCandidateWindow()
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

    private func showCandidateWindow() {
        candidateWindow.update()
        candidateWindow.show()
        isCandidateWindowVisible = true
    }

    private func hideCandidateWindow() {
        candidateWindow.hide()
        isCandidateWindowVisible = false
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
