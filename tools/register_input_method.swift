import Carbon
import Foundation

private let expectedArgumentCount = 3
private let registrationAttemptCount = 3
private let registrationRetryDelay: TimeInterval = 1.0

guard CommandLine.arguments.count == expectedArgumentCount else {
    fputs("Usage: swift tools/register_input_method.swift <input-method-app> <bundle-id>\n", stderr)
    exit(64)
}

let inputMethodPath = CommandLine.arguments[1]
let expectedBundleIdentifier = CommandLine.arguments[2]
let inputMethodURL = URL(fileURLWithPath: inputMethodPath, isDirectory: true)

guard FileManager.default.fileExists(atPath: inputMethodURL.path) else {
    fputs("Input method was not found: \(inputMethodURL.path)\n", stderr)
    exit(66)
}

for attemptNumber in 1...registrationAttemptCount {
    let registerStatus = TISRegisterInputSource(inputMethodURL as CFURL)
    guard registerStatus == noErr else {
        fputs("TISRegisterInputSource attempt \(attemptNumber) failed with status \(registerStatus): \(inputMethodURL.path)\n", stderr)
        exit(1)
    }

    if attemptNumber < registrationAttemptCount {
        Thread.sleep(forTimeInterval: registrationRetryDelay)
    }
}

guard inputSourceExists(bundleIdentifier: expectedBundleIdentifier) else {
    fputs("Registered input method was not found in Text Input Sources: \(expectedBundleIdentifier)\n", stderr)
    exit(2)
}

private func inputSourceExists(bundleIdentifier: String) -> Bool {
    guard let unmanagedInputSources = TISCreateInputSourceList(nil, true) else {
        return false
    }

    guard let inputSources = unmanagedInputSources.takeRetainedValue() as? [TISInputSource] else {
        return false
    }

    return inputSources.contains { inputSource in
        return stringProperty(inputSource, kTISPropertyBundleID) == bundleIdentifier
    }
}

private func stringProperty(_ inputSource: TISInputSource, _ propertyKey: CFString) -> String? {
    guard let propertyPointer = TISGetInputSourceProperty(inputSource, propertyKey) else {
        return nil
    }

    return Unmanaged<CFString>.fromOpaque(propertyPointer).takeUnretainedValue() as String
}
