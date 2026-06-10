import AppKit
import InputMethodKit
import RomaFlowImeCore

if let command = CommandLine.arguments.dropFirst().first {
    InputSourceInstaller.run(command: command)
}

let fallbackBundleIdentifier = "me.matsumo.inputmethod.RomaFlow"
let bundleIdentifier = Bundle.main.bundleIdentifier ?? fallbackBundleIdentifier
let connectionName = Bundle.main.object(forInfoDictionaryKey: "InputMethodConnectionName") as? String
    ?? "\(bundleIdentifier)_Connection"

let engine = RomaFlowEngine()
let server = IMKServer(name: connectionName, bundleIdentifier: bundleIdentifier)

NSLog("RomaFlow input method server started: %@", engine.smokeText())
NSApplication.shared.run()
