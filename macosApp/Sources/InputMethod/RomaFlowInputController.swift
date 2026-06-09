import Foundation
import InputMethodKit
import RomaFlowImeCore

@objc(RomaFlowInputController)
final class RomaFlowInputController: IMKInputController {
    private let engine = RomaFlowEngine()

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        super.init(server: server, delegate: delegate, client: inputClient)

        NSLog("RomaFlowInputController connected: \(engine.smokeText())")
    }
}
