import AppKit
import Foundation
import InputMethodKit
import RomaFlowImeCore

@objc(RomaFlowInputController)
final class RomaFlowInputController: IMKInputController {
    private enum InputCommand {
        case commitComposition
        case deleteBackward
        case cancelComposition
    }

    private enum KeyCode {
        static let returnKey: UInt16 = 36
        static let space: UInt16 = 49
        static let delete: UInt16 = 51
        static let escape: UInt16 = 53
        static let keypadEnter: UInt16 = 76
    }

    private let engine = RomaFlowEngine()
    private var inputClient: Any?

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        self.inputClient = inputClient

        super.init(server: server, delegate: delegate, client: inputClient)

        NSLog("RomaFlowInputController connected: \(engine.smokeText())")
    }

    override func handle(_ event: NSEvent!, client inputClient: Any!) -> Bool {
        guard let keyEvent = event else { return false }
        guard keyEvent.type == .keyDown else { return false }
        guard hasPassthroughModifier(keyEvent) == false else { return false }

        if let inputCommand = inputCommand(from: keyEvent) {
            return handle(inputCommand, inputClient: inputClient)
        }

        guard let inputText = inputText(from: keyEvent) else { return false }

        _ = engine.inputText(text: inputText)
        updateComposition()

        return true
    }

    override func commitComposition(_ sender: Any!) {
        guard engine.hasComposition() else { return }

        commitCurrentComposition(to: sender)
    }

    override func composedString(_ sender: Any!) -> Any! {
        return engine.currentComposition()
    }

    override func originalString(_ sender: Any!) -> NSAttributedString! {
        return NSAttributedString(string: engine.currentComposition())
    }

    override func selectionRange() -> NSRange {
        return NSRange(location: engine.currentComposition().utf16.count, length: 0)
    }

    private func hasPassthroughModifier(_ event: NSEvent) -> Bool {
        let passthroughFlags: NSEvent.ModifierFlags = [.command, .control, .option]
        let activeFlags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)

        return activeFlags.intersection(passthroughFlags).isEmpty == false
    }

    private func inputCommand(from event: NSEvent) -> InputCommand? {
        switch event.keyCode {
        case KeyCode.returnKey, KeyCode.space, KeyCode.keypadEnter:
            return .commitComposition
        case KeyCode.delete:
            return .deleteBackward
        case KeyCode.escape:
            return .cancelComposition
        default:
            return nil
        }
    }

    private func handle(_ inputCommand: InputCommand, inputClient: Any?) -> Bool {
        switch inputCommand {
        case .commitComposition:
            guard engine.hasComposition() else { return false }

            commitCurrentComposition(to: inputClient)

            return true
        case .deleteBackward:
            guard engine.hasComposition() else { return false }

            _ = engine.deleteBackward()
            updateComposition()

            return true
        case .cancelComposition:
            guard engine.hasComposition() else { return false }

            _ = engine.clearComposition()
            updateComposition()

            return true
        }
    }

    private func inputText(from event: NSEvent) -> String? {
        guard let characters = event.characters else { return nil }
        guard characters.unicodeScalars.count == 1 else { return nil }
        guard let unicodeScalar = characters.unicodeScalars.first else { return nil }

        let isLowercaseAscii = unicodeScalar.value >= 97 && unicodeScalar.value <= 122
        guard isLowercaseAscii else { return nil }

        return characters
    }

    private func commitCurrentComposition(to inputClient: Any?) {
        let committedText = engine.commitComposition()

        updateComposition()

        guard committedText.isEmpty == false else { return }

        insertText(committedText, into: inputClient)
    }

    private func insertText(_ text: String, into inputClient: Any?) {
        guard let textInputClient = resolveTextInputClient(inputClient) else {
            NSLog("RomaFlowInputController could not resolve NSTextInputClient for commit")

            return
        }

        textInputClient.insertText(text, replacementRange: defaultReplacementRange())
    }

    private func resolveTextInputClient(_ inputClient: Any?) -> NSTextInputClient? {
        if let textInputClient = inputClient as? NSTextInputClient {
            return textInputClient
        }

        return self.inputClient as? NSTextInputClient
    }

    private func defaultReplacementRange() -> NSRange {
        return NSRange(location: NSNotFound, length: 0)
    }
}
