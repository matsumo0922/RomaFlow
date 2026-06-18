import AppKit
import Foundation
import InputMethodKit
import RomaFlowImeCore

@objc(RomaFlowInputController)
final class RomaFlowInputController: IMKInputController {
    private let engine = RomaFlowEngine()

    // 文節単位の変換候補を表示する縦一列の候補窓 (第5章)。文節選択中だけ表示する。
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

    // 候補窓に一度に並べる候補の上限。窓が縦に伸びすぎて入力位置を覆わないよう控えめにする。
    private let maxVisibleCandidates = 9

    // 候補窓の候補テキストの描画フォントサイズ。標準より小さくして窓を低くする (選択キーは影響を受けない)。
    private let candidateFontSize: CGFloat = 12

    // marked text の下線スタイル。未選択 clause は細い実線、選択中 clause は太い実線。
    private let thinUnderline = NSUnderlineStyle.single.rawValue

    private let thickUnderline = NSUnderlineStyle.thick.rawValue

    // 実行中の AI 変換 Task。後続入力で stale 結果を破棄するためにキャンセルする。
    private var conversionTask: Task<Void, Never>?

    // 実行中の call2 (単語候補) Task。文節移動・確定・窓閉・新規入力で stale なのでキャンセルする。
    private var candidatesTask: Task<Void, Never>?

    // 候補窓を表示中かどうか。表示中はキー入力を候補窓操作へ振り分ける。
    private var isCandidateWindowVisible = false

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        candidateWindow = IMKCandidates(server: server, panelType: kIMKSingleColumnScrollingCandidatePanel)
        super.init(server: server, delegate: delegate, client: inputClient)

        configureCandidateWindowAppearance()

        NSLog("RomaFlowInputController connected: %@", engine.smokeText())
    }

    // 候補窓の見た目を設定する。NSFontAttributeName で候補テキストのフォントサイズを下げて窓を低くする。
    // IMKCandidates.setAttributes の NSFontAttributeName はフォント (= サイズ) を指定するための公式 key
    // (InputMethodKit SDK header 記載)。選択キーのフォントには影響しない。
    private func configureCandidateWindowAppearance() {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: candidateFontSize),
        ]

        candidateWindow.setAttributes(attributes)
    }

    // 入力処理はこのメソッドに集約する。処理したイベントでは super を呼ばず、二重更新を防ぐ。
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, event.type == .keyDown, let client = sender as? IMKTextInput else {
            return false
        }

        // 候補窓を表示中は、まず候補操作 (↑↓ 移動・Enter 確定・Esc 閉じ・←→ 隣文節) として処理する。
        if isCandidateWindowVisible {
            return handleCandidateWindowEvent(event, client: client)
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
            // 修飾付き ←（Cmd+← 等）は単語選択ではなくアプリ側 shortcut なので下の commit→pass-through へ流す。
            if hasCommandLikeModifier(event) { break }

            return handleMoveSelection(toRight: false, client: client)
        case keyCodeArrowRight:
            if hasCommandLikeModifier(event) { break }

            return handleMoveSelection(toRight: true, client: client)
        default:
            break
        }

        // Cmd / Ctrl / Option を伴うキーはアプリ側 shortcut なので IME では処理しない。
        // ただし active composition があるときは marked text と engine state を残さないよう、
        // WYSIWYG で確定してから pass-through する (issue #8)。
        if hasCommandLikeModifier(event) {
            _ = performCommit(with: client)

            return false
        }

        return handlePrintable(event, client: client)
    }

    override func setValue(_ value: Any!, forTag tag: Int, client sender: Any!) {
        super.setValue(value, forTag: tag, client: sender)

        // 入力モード切替時は in-flight の AI 変換 / call2 を止め、候補窓を閉じ、表示中の composition を
        // WYSIWYG 確定する (issue #8)。これをしないと、切替後に遅れて返った結果が marked text として復活する。
        cancelPendingConversion()
        cancelPendingCandidates()
        hideCandidateWindow()
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
        cancelPendingCandidates()
        hideCandidateWindow()
        _ = performCommit(with: client)
    }

    // Cmd / Ctrl / Option のいずれかを伴うか（アプリ側 shortcut とみなす修飾キー判定）。
    private func hasCommandLikeModifier(_ event: NSEvent) -> Bool {
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)

        return !modifiers.intersection([.command, .control, .option]).isEmpty
    }

    // 修飾なしの数字キー 1〜9（番号付き候補の選択キー）か。0 や修飾付き・複数文字は対象外。
    private func isUnmodifiedDigitSelectionKey(_ event: NSEvent) -> Bool {
        if hasCommandLikeModifier(event) {
            return false
        }

        guard let characters = event.charactersIgnoringModifiers, characters.count == 1 else {
            return false
        }

        return ("1"..."9").contains(characters)
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

        // ↑/↓ や Home/End/F-key は NSEvent.characters が function-key 系 private-use Unicode
        // (U+F700...U+F8FF) になる。これらは printable ではないので romaji buffer に混ぜず pass-through する。
        // (←/→ は handle 側の keyCode 分岐で moveSelection に接続済み。候補窓がない B1a で ↑/↓ は何もしない)
        if containsFunctionKey(characters) {
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

    // NSEvent.characters に function-key 系 private-use Unicode (U+F700...U+F8FF) を含むか。
    // 矢印・Home/End・F-key などの非印字キーを printable 入力から除外するために使う。
    private func containsFunctionKey(_ characters: String) -> Bool {
        let functionKeyRange: ClosedRange<UInt32> = 0xF700...0xF8FF

        return characters.unicodeScalars.contains { scalar in
            functionKeyRange.contains(scalar.value)
        }
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
        // engine.convert() は冒頭で pendingRomaji を finalize して状態を変える。後続入力で cancel 済みの
        // 古い Task が convert() に入り、新しい pendingRomaji を確定してしまわないよう、呼び出し前に弾く。
        if Task.isCancelled {
            return
        }

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

    private func cancelPendingCandidates() {
        candidatesTask?.cancel()
        candidatesTask = nil
    }

    // ←/→: 変換済＋未変換かなの文節選択カーソルを移動する。未確定でなければアプリ側へ流す。
    // 文節を選択したら候補窓を開き (自明候補が即表示) call2 を非同期発火、選択が外れたら窓を閉じる。
    // engine.moveSelection は内部で candidate session を invalidate するので、移動のたび call2 stale guard が働く。
    private func handleMoveSelection(toRight: Bool, client: IMKTextInput) -> Bool {
        guard engine.hasComposition() else {
            return false
        }

        let preedit = toRight ? engine.moveSelectionRight() : engine.moveSelectionLeft()
        updateMarkedText(preedit, client: client)

        syncCandidateWindowToSelection(client: client)

        return true
    }

    // 現在の文節選択に候補窓の表示状態を合わせる。候補のある文節を選択中なら開く/reload、それ以外は閉じる。
    // call2 は文節が変わるたびに旧 Task を cancel して新規発火する (engine 側の stale guard と二重で守る)。
    // 文節ハイライト (marked text) は呼び出し元の moveSelection 側で更新済みなので、ここは窓だけ管理する。
    private func syncCandidateWindowToSelection(client: IMKTextInput) {
        let hasSelectedClause = engine.selectedSegmentIndex() >= 0

        // 未変換 tail 文節など候補が無い文節 (candidateCount == 0) では空の候補窓を出さない。
        // candidates(_:) override も [] を返すので、show 経路でも同じ条件で揃える。
        let hasCandidates = engine.candidateCount() > 0
        let shouldShowWindow = hasSelectedClause && hasCandidates

        guard shouldShowWindow else {
            hideCandidateWindow()
            cancelPendingCandidates()

            return
        }

        showCandidateWindow()
        startWordCandidatesTask(client: client)
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

    // 候補窓表示中のキー処理。↑↓ は窓へ転送して選択移動、Enter は確定 (prefix lock)、Esc は窓だけ閉じ、
    // ←→ は隣の文節へ移る。それ以外は表示中の preedit を残したまま候補 session を閉じて通常処理へ落とす。
    private func handleCandidateWindowEvent(_ event: NSEvent, client: IMKTextInput) -> Bool {
        // 修飾なしの数字キー 1〜9 は番号付き候補の選択として候補窓へ転送する (選択時 candidateSelected が発火)。
        // keyCode は配列依存なので、入力文字が単一の "1"〜"9" かどうかで判定する。0 や修飾付きは対象外。
        if isUnmodifiedDigitSelectionKey(event) {
            candidateWindow.interpretKeyEvents([event])

            return true
        }

        switch Int(event.keyCode) {
        case keyCodeArrowUp, keyCodeArrowDown, keyCodeReturn, keyCodeKeypadEnter:
            // 上下 navigation・確定キー・数字キーは候補窓へ転送する。確定時は candidateSelected(_:) が呼ばれる。
            // navigation 中は candidateSelectionChanged(_:) 経由で live preview が走る (発火しない client では preview なし)。
            candidateWindow.interpretKeyEvents([event])

            return true
        case keyCodeEscape:
            return closeCandidateSession(client: client)
        case keyCodeArrowLeft:
            if hasCommandLikeModifier(event) { break }

            // 隣の文節へ移る前に現在の候補を確定＋lock する (azooKey 式・通った文節まで lock)。
            // lockSelectedClause が選択 index を維持するので、続く moveSelection で隣文節へ進める。
            _ = engine.lockSelectedClause()

            return handleMoveSelection(toRight: false, client: client)
        case keyCodeArrowRight:
            if hasCommandLikeModifier(event) { break }

            // 隣の文節へ移る前に現在の候補を確定＋lock する (azooKey 式・通った文節まで lock)。
            _ = engine.lockSelectedClause()

            return handleMoveSelection(toRight: true, client: client)
        case keyCodeTab:
            // Tab は再変換 (lock prefix を保持して tail だけ再変換)。窓を閉じてから変換へ入る。
            cancelPendingConversion()
            cancelPendingCandidates()
            hideCandidateWindow()
            _ = engine.closeCandidates()

            return handleConvert(event, client: client)
        default:
            break
        }

        // 上記以外 (印字文字・backspace 等) は preview を破棄して候補 session を閉じ、通常の handle 経路で処理する。
        _ = closeCandidateSession(client: client)

        return fallthroughAfterCandidateClose(event, client: client)
    }

    // 候補窓を閉じ、preview を破棄して文節選択 (Word) へ戻す。preedit は marked のまま残る。
    private func closeCandidateSession(client: IMKTextInput) -> Bool {
        cancelPendingCandidates()
        hideCandidateWindow()
        let preedit = engine.closeCandidates()
        updateMarkedText(preedit, client: client)

        return true
    }

    // 候補窓を閉じた後、navigation でないキーを通常経路へ落とす。修飾なしの印字文字なら handlePrintable で
    // 表示中の変換結果へ追記入力し、それ以外 (backspace・ショートカット等) は通常の handle 分岐へ委ねる。
    private func fallthroughAfterCandidateClose(_ event: NSEvent, client: IMKTextInput) -> Bool {
        switch Int(event.keyCode) {
        case keyCodeDelete:
            return handleBackspace(with: client)
        default:
            break
        }

        if hasCommandLikeModifier(event) {
            _ = performCommit(with: client)

            return false
        }

        return handlePrintable(event, client: client)
    }

    // 候補窓へ表示する候補を返す。IMKCandidates.update() から呼ばれる (第5章 5.2)。
    // Swift Export 越しに List を出せないため、engine の indexed accessor を回して [String] に組む。
    // 窓が縦に伸びすぎないよう、表示件数は maxVisibleCandidates でキャップする (空窓 gate は syncCandidateWindowToSelection 側に据え置き)。
    override func candidates(_ sender: Any!) -> [Any] {
        let count = Int(engine.candidateCount())

        guard count > 0 else {
            return []
        }

        let visibleCount = min(count, maxVisibleCandidates)
        var collected: [String] = []
        for index in 0..<visibleCount {
            collected.append(engine.candidateText(index: Int32(index)))
        }

        return collected
    }

    // 候補窓で選択中の候補が変わったとき (↑↓ navigation 中) に呼ばれる live preview callback。
    // engine.previewCandidate(text:) で marked text を更新するだけで確定はしない。
    // この callback が発火しない client では preview が出ないだけで confirm 経路 (candidateSelected) は成立する。
    override func candidateSelectionChanged(_ candidateString: NSAttributedString!) {
        let preview = candidateString?.string ?? ""

        guard !preview.isEmpty, let client = client() as? IMKTextInput else {
            return
        }

        let preedit = engine.previewCandidate(text: preview)
        updateMarkedText(preedit, client: client)
    }

    // 候補窓で候補が確定された (Enter / クリック / 数字キー) ときに呼ばれる (第5章 5.4)。
    // B2 は confirm = prefix lock であり commit ではない。engine.confirmCandidate(text:) で選択文節へ適用＋
    // 先頭からその文節までを Locked にし、窓を閉じる。確定後はカーソルを文末へ移す (選択解除・selectedSegmentIndex == -1)。
    // 次の Enter (窓が閉じた状態の performCommit) で全体 commit する。
    override func candidateSelected(_ candidateString: NSAttributedString!) {
        let selected = candidateString?.string ?? ""

        guard !selected.isEmpty, let client = client() as? IMKTextInput else {
            return
        }

        // IMKCandidates の event 処理中に呼ばれるため、再入を避けて main queue に積む (reference 5.4)。
        DispatchQueue.main.async { [weak self] in
            self?.confirmSelectedCandidate(selected, client: client)
        }
    }

    // 選択候補を prefix lock として確定し、候補窓を閉じて marked text を更新する。candidateSelected の本体。
    // confirmCandidate の戻り preedit をそのまま反映する。confirm 後は selection=None なので
    // updateMarkedText → applyClauseSegments が selectedRange=nil を返し、buildMarkedText がキャレットを文末へ置く。
    private func confirmSelectedCandidate(_ text: String, client: IMKTextInput) {
        cancelPendingCandidates()
        let preedit = engine.confirmCandidate(text: text)
        hideCandidateWindow()
        updateMarkedText(preedit, client: client)
    }

    // 候補窓を表示 (または別文節へ移ったので reload) する。候補が無ければ表示しない。
    private func showCandidateWindow() {
        candidateWindow.update()
        candidateWindow.show()
        isCandidateWindowVisible = true
    }

    private func hideCandidateWindow() {
        candidateWindow.hide()
        isCandidateWindowVisible = false
    }

    // 選択中の文節について call2 (LLM 単語候補) を非同期に要求し、結果を自明候補へマージして窓を reload する。
    // 旧 Task を cancel してから発火する。stale 結果は engine 側の candidateRequestId guard でも弾かれる。
    private func startWordCandidatesTask(client: IMKTextInput) {
        cancelPendingCandidates()

        candidatesTask = Task { @MainActor [weak self] in
            await self?.runWordCandidates(client: client)
        }
    }

    // call2 を実行し、結果を main スレッドで候補へ反映して窓を reload する。失敗・キャンセル・空結果・stale は据え置く。
    @MainActor
    private func runWordCandidates(client: IMKTextInput) async {
        if Task.isCancelled {
            return
        }

        let result = (try? await engine.requestWordCandidates()) ?? ""

        if Task.isCancelled || result.isEmpty {
            return
        }

        let preedit = engine.applyWordCandidates(result: result)

        guard isCandidateWindowVisible else {
            return
        }

        candidateWindow.update()
        updateMarkedText(preedit, client: client)
    }

    private func updateMarkedText(_ text: String, client: IMKTextInput) {
        if text.isEmpty {
            clearMarkedText(client)

            return
        }

        let marked = buildMarkedText(text)
        client.setMarkedText(marked.attributed, selectionRange: marked.selectionRange, replacementRange: notFoundRange)
    }

    // preedit 文字列に engine の display segment 構造を重ね、各 clause へ下線と clause 番号を付けた
    // NSAttributedString を組む。全体は細い実線下線で覆い、clause 境界は markedClauseSegment 属性で示す。
    // 選択中 clause があればその範囲へ、なければ末尾へキャレットを置く。
    private func buildMarkedText(_ text: String) -> (attributed: NSAttributedString, selectionRange: NSRange) {
        let markedString = text as NSString
        let totalLength = markedString.length
        let attributed = NSMutableAttributedString(string: text)

        let fullRange = NSRange(location: 0, length: totalLength)
        attributed.addAttribute(.underlineStyle, value: thinUnderline, range: fullRange)

        let selectedRange = applyClauseSegments(to: attributed, totalLength: totalLength)
        let caretRange = selectedRange ?? NSRange(location: totalLength, length: 0)

        return (attributed, caretRange)
    }

    // 各 display segment の UTF-16 長を先頭から積み上げ、clause 範囲に markedClauseSegment 番号を付ける。
    // 選択中 clause（selectedSegmentIndex）には太線下線とシステム選択背景色を付け、その範囲を戻り値で返す。
    // 未選択（-1）や範囲外なら nil を返す。
    private func applyClauseSegments(to attributed: NSMutableAttributedString, totalLength: Int) -> NSRange? {
        let segmentCount = Int(engine.segmentCount())
        let selectedIndex = Int(engine.selectedSegmentIndex())
        var location = 0
        var selectedRange: NSRange?

        for index in 0..<segmentCount {
            let segmentLength = (engine.segmentText(index: Int32(index)) as NSString).length
            if segmentLength == 0 {
                continue
            }

            let segmentEnd = location + segmentLength
            if segmentEnd > totalLength {
                break
            }

            let clauseRange = NSRange(location: location, length: segmentLength)
            attributed.addAttribute(.markedClauseSegment, value: NSNumber(value: index), range: clauseRange)

            if index == selectedIndex {
                attributed.addAttribute(.underlineStyle, value: thickUnderline, range: clauseRange)
                attributed.addAttribute(.backgroundColor, value: NSColor.selectedTextBackgroundColor, range: clauseRange)
                selectedRange = clauseRange
            }

            location = segmentEnd
        }

        return selectedRange
    }

    private func clearMarkedText(_ client: IMKTextInput) {
        client.setMarkedText("", selectionRange: NSRange(location: 0, length: 0), replacementRange: notFoundRange)
    }
}
