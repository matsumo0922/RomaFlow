import Foundation
import InputMethodKit
import RomaFlowImeCore

@objc(RomaFlowInputController)
final class RomaFlowInputController: IMKInputController {
    private let engine = RomaFlowEngine()

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        super.init(server: server, delegate: delegate, client: inputClient)

        NSLog("RomaFlowInputController connected: %@", engine.smokeText())
    }

    override func inputText(_ string: String!, client sender: Any!) -> Bool {
        guard let string, let client = sender as? IMKTextInput else {
            return false
        }

        // Enter (\r) や Tab (\t) などの制御文字は OS のデフォルト動作に流す
        let containsControlCharacters = string.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        if containsControlCharacters {
            return false
        }

        client.insertText(string, replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
        NSLog("RomaFlow inputText: %@", string)

        return true
    }

    override func setValue(_ value: Any!, forTag tag: Int, client sender: Any!) {
        super.setValue(value, forTag: tag, client: sender)

        guard let inputModeID = value as? String else {
            return
        }

        NSLog("RomaFlow input mode changed: %@", inputModeID)
    }
}
