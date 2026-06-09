import RomaFlowImeCore
import SwiftUI

struct ContentView: View {
    private let smokeText = RomaFlowEngine().smokeText()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("RomaFlow Harness")
                .font(.title)

            Text(smokeText)
                .font(.body)
        }
        .padding(24)
        .frame(minWidth: 360, minHeight: 160)
    }
}
