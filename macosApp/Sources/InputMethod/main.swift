import AppKit
import InputMethodKit
import RomaFlowImeCore

private let connectionName = "RomaFlowInputMethod_Connection"
private let fallbackBundleIdentifier = "me.matsumo.romaflow.inputmethod"
private let bundleIdentifier = Bundle.main.bundleIdentifier ?? fallbackBundleIdentifier
private let engine = RomaFlowEngine()
private let server = IMKServer(name: connectionName, bundleIdentifier: bundleIdentifier)

NSLog("RomaFlow input method server started: \(engine.smokeText())")
RunLoop.current.run()
