import AppKit

// RomaFlow IME のメニューバー用テンプレートアイコン (main.tiff / en.tiff) を生成するスクリプト。
// 使い方: swift scripts/generate_ime_icons.swift <output-dir>
// 黒グリフ + 透過背景で 16px / 32px(@2x) を描画し、tiffutil で 1 つの .tiff にまとめる。

func renderGlyph(glyph: String, pixelSize: Int, to url: URL) {
    let bitmapRep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: pixelSize,
        pixelsHigh: pixelSize,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    )!

    let context = NSGraphicsContext(bitmapImageRep: bitmapRep)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = context

    let attributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.boldSystemFont(ofSize: CGFloat(pixelSize) * 0.75),
        .foregroundColor: NSColor.black,
    ]
    let attributedGlyph = NSAttributedString(string: glyph, attributes: attributes)
    let glyphSize = attributedGlyph.size()
    let drawPoint = NSPoint(
        x: (CGFloat(pixelSize) - glyphSize.width) / 2,
        y: (CGFloat(pixelSize) - glyphSize.height) / 2
    )
    attributedGlyph.draw(at: drawPoint)

    NSGraphicsContext.restoreGraphicsState()

    let pngData = bitmapRep.representation(using: .png, properties: [:])!
    try! pngData.write(to: url)
}

func makeIcon(glyph: String, fileName: String, outputDirectory: URL) {
    let temporaryDirectory = FileManager.default.temporaryDirectory
    let basePng = temporaryDirectory.appendingPathComponent("\(fileName)_16.png")
    let retinaPng = temporaryDirectory.appendingPathComponent("\(fileName)_32.png")

    renderGlyph(glyph: glyph, pixelSize: 16, to: basePng)
    renderGlyph(glyph: glyph, pixelSize: 32, to: retinaPng)

    let outputTiff = outputDirectory.appendingPathComponent("\(fileName).tiff")
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/tiffutil")
    process.arguments = ["-cathidpicheck", basePng.path, retinaPng.path, "-out", outputTiff.path]
    try! process.run()
    process.waitUntilExit()
    precondition(process.terminationStatus == 0, "tiffutil failed for \(fileName)")

    print("generated: \(outputTiff.path)")
}

let arguments = CommandLine.arguments
precondition(arguments.count == 2, "usage: swift generate_ime_icons.swift <output-dir>")

let outputDirectory = URL(fileURLWithPath: arguments[1], isDirectory: true)
try! FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

makeIcon(glyph: "ろ", fileName: "main", outputDirectory: outputDirectory)
makeIcon(glyph: "R", fileName: "en", outputDirectory: outputDirectory)
