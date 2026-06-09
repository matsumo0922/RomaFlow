import RomaFlowImeCore
import SwiftUI

struct ContentView: View {
    @State private var inputText = ""

    private let smokeText = RomaFlowEngine().smokeText()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("RomaFlow Harness")
                .font(.title)

            Text(smokeText)
                .font(.body)

            TextEditor(text: $inputText)
                .font(.system(.body, design: .monospaced))
                .frame(minHeight: 160)
                .overlay {
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(.quaternary)
                }

            Text("\(inputText.count) chars")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(minWidth: 480, minHeight: 320)
    }
}
