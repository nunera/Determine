/// Display/context-image geometry only; never an input to hazard decisions.
public enum CameraImagePixels {
    /// Reflect each packed RGBA row in place without reversing row order or channel bytes.
    /// The caller supplies a tightly packed, nonempty width × height buffer.
    public static func mirrorHorizontally(_ pixels: UnsafeMutableBufferPointer<UInt32>, width: Int, height: Int) {
        precondition(width > 0 && height > 0 && pixels.count / height == width && pixels.count % height == 0)
        for row in 0..<height {
            let start = row * width
            for column in 0..<(width / 2) {
                pixels.swapAt(start + column, start + width - 1 - column)
            }
        }
    }
}
