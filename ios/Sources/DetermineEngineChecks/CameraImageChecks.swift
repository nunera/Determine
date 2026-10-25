import DetermineEngine

func cameraImageChecks(_ t: Harness) {
    func reflected(_ input: [UInt32], width: Int, height: Int) -> [UInt32] {
        var pixels = input
        pixels.withUnsafeMutableBufferPointer { CameraImagePixels.mirrorHorizontally($0, width: width, height: height) }
        return pixels
    }
    t.test("camera image: horizontal correction swaps left/right without reversing top/bottom or channel bytes") {
        let pixels: [UInt32] = [0xFF0000FF, 0x00FF00FF, 0x0000FFFF, 0xFFFFFFFF,
                                0x12345678, 0x87654321, 0x11223344, 0x55667788]
        t.eq([0xFFFFFFFF, 0x0000FFFF, 0x00FF00FF, 0xFF0000FF,
              0x55667788, 0x11223344, 0x87654321, 0x12345678] as [UInt32],
             reflected(pixels, width: 4, height: 2))
    }
    t.test("camera image: odd row widths keep the center pixel and do not exchange rows") {
        t.eq([3, 2, 1, 6, 5, 4] as [UInt32], reflected([1, 2, 3, 4, 5, 6], width: 3, height: 2))
    }
    t.test("camera image: narrow frames and applying the correction twice preserve the original") {
        t.eq([1, 2, 3] as [UInt32], reflected([1, 2, 3], width: 1, height: 3))
        let original: [UInt32] = [1, 2, 3, 4, 5, 6]
        t.eq(original, reflected(reflected(original, width: 3, height: 2), width: 3, height: 2))
    }
}
