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
    private let keyCodeTab = 48
    private let keyCodeArrowLeft = 123
    private let keyCodeArrowRight = 124

    // insertText / setMarkedText で「置換範囲を指定しない」ことを示す range
    private let notFoundRange = NSRange(location: NSNotFound, length: 0)

    // 実行中の AI 変換 Task。後続入力で stale 結果を破棄するためにキャンセルする。
    private var conversionTask: Task<Void, Never>?

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        super.init(server: server, delegate: delegate, client: inputClient)

        NSLog("RomaFlowInputController connected: %@", engine.smokeText())
    }

    // 入力処理はこのメソッドに集約する。処理したイベントでは super を呼ばず、二重更新を防ぐ。
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, event.type == .keyDown, let client = sender as? IMKTextInput else {
            return false
        }

        // 新しいキー入力が来たら実行中の AI 変換は stale なのでキャンセルする。
        cancelPendingConversion()

        switch Int(event.keyCode) {
        case keyCodeReturn, keyCodeKeypadEnter:
            return performCommit(with: client)
        case keyCodeEscape:
            return cancelComposition(with: client)
        case keyCodeDelete:
            return handleBackspace(with: client)
        case keyCodeTab:
            return handleConvert(event, client: client)
        case keyCodeArrowLeft:
            return handleMoveSelection(toRight: false, client: client)
        case keyCodeArrowRight:
            return handleMoveSelection(toRight: true, client: client)
        default:
            break
        }

        // Cmd / Ctrl / Option を伴うキーはアプリ側 shortcut なので IME では処理しない。
        // ただし active composition があるときは marked text と engine state を残さないよう、
        // WYSIWYG で確定してから pass-through する (issue #8)。
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let hasCommandLikeModifier = !modifiers.intersection([.command, .control, .option]).isEmpty
        if hasCommandLikeModifier {
            _ = performCommit(with: client)

            return false
        }

        return handlePrintable(event, client: client)
    }

    override func setValue(_ value: Any!, forTag tag: Int, client sender: Any!) {
        super.setValue(value, forTag: tag, client: sender)

        // 入力モード切替時は in-flight の AI 変換を止め、表示中の composition を WYSIWYG 確定する (issue #8)。
        // これをしないと、切替後に遅れて返った変換結果が marked text として復活してしまう。
        cancelPendingConversion()
        if let client = sender as? IMKTextInput {
            _ = performCommit(with: client)
        }

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

        cancelPendingConversion()
        _ = performCommit(with: client)
    }

    // 印字可能な文字を engine に渡し、変換後の preedit を未確定 (marked) テキストとして表示する。
    // 変換済 segments があっても確定はせず、追記分は未変換かな tail として混在 preedit に積む (frozen かな)。
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

        // 未入力状態の space はアプリにそのまま空白を入れさせる (空の marked text を出さない)
        if characters == " ", !engine.hasComposition() {
            return false
        }

        let preedit = engine.inputRomaji(text: characters)
        updateMarkedText(preedit, client: client)

        return true
    }

    // Tab: 打った通りのかな全体を AI ConversionProvider で非同期に全文変換し、結果を marked テキストへ反映する。
    // await 中はかな marked を維持する。変換するのは修飾キーなしの Tab だけ。Cmd+Tab / Shift+Tab などは
    // アプリ側のショートカットなので、未確定中なら WYSIWYG で確定してから false を返して流す。
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

        // 変換開始時点で pendingRomaji を finalize し、await 中のかな marked を確定後のかな (おn→おん) へ揃える。
        // これで API key 未設定・空結果・キャンセルでも、表示中の marked text と commit 内容が一致する。
        let finalizedPreedit = engine.finalizePendingRomaji()
        updateMarkedText(finalizedPreedit, client: client)

        // 変換結果は非同期で届く。後続入力で破棄できるよう Task を保持する。
        conversionTask = Task { @MainActor [weak self] in
            await self?.runConversion(client: client)
        }

        return true
    }

    // AI 変換を実行し、結果を main スレッドで状態へ反映する。失敗・キャンセル・空結果は据え置く。
    @MainActor
    private func runConversion(client: IMKTextInput) async {
        let result = (try? await engine.convert()) ?? ""

        if Task.isCancelled || result.isEmpty {
            return
        }

        let applied = engine.applyConversion(result: result)
        guard !applied.isEmpty else {
            return
        }

        updateMarkedText(applied, client: client)
    }

    private func cancelPendingConversion() {
        conversionTask?.cancel()
        conversionTask = nil
    }

    // ←/→: 変換済＋未変換かなの単語選択カーソルを移動する。未確定でなければアプリ側へ流す。
    // B1a は plain marked text のため選択強調は描画せず、内部の選択状態だけ更新する (強調は B1c)。
    private func handleMoveSelection(toRight: Bool, client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let preedit = toRight ? engine.moveSelectionRight() : engine.moveSelectionLeft()
        updateMarkedText(preedit, client: client)

        return true
    }

    // 未確定中なら確定文字列を挿入し marked テキストを消す。未確定でなければ false を返してアプリ側に流す。
    // Enter と involuntary commit (commitComposition) の確定経路がこのメソッドを共有する。
    private func performCommit(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let committed = engine.commit()
        client.insertText(committed, replacementRange: notFoundRange)
        clearMarkedText(client)

        return true
    }

    // Escape: 変換済なら打った通りのかなへ戻し、未変換なら composition を破棄する (engine.cancel が両者を返す)。
    // 未確定でなければアプリ側に流す。
    private func cancelComposition(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let preedit = engine.cancel()
        updateMarkedText(preedit, client: client)

        return true
    }

    // Backspace: 未確定中なら優先順位 (pendingRomaji → 末尾) に従って 1 単位削り表示更新。
    // 未確定でなければアプリ側に流す。
    private func handleBackspace(with client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let preedit = engine.deleteBackward()
        updateMarkedText(preedit, client: client)

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
