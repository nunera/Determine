#if canImport(CoreGraphics)
import CoreGraphics
import DetermineEngine
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Canonical headband-image reflection for preview and uploaded JPEGs. Run off the engine/main queues.
enum CameraImageCorrection {
    static func image(_ data: Data, mirrored: Bool) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let original = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        else { return nil }
        guard mirrored else { return original }
        let width = original.width, height = original.height
        guard (1...2048).contains(width), (1...2048).contains(height) else { return nil }
        var pixels = [UInt32](repeating: 0, count: width * height)
        return pixels.withUnsafeMutableBufferPointer { buffer in
            let info = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info)
            else { return nil }
            context.draw(original, in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
            CameraImagePixels.mirrorHorizontally(buffer, width: width, height: height)
            // makeImage creates a snapshot; it does not expose the temporary buffer's lifetime.
            return context.makeImage()
        }
    }

    static func mirroredJPEG(_ jpeg: [UInt8]) -> [UInt8]? {
        guard let corrected = image(Data(jpeg), mirrored: true) else { return nil }
        let result = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(result as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, corrected, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return [UInt8](result as Data)
    }
}
#endif
