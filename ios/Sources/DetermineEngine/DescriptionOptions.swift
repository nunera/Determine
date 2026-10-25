import Foundation

public enum DescriptionLength: String, CaseIterable, Codable, Sendable, Identifiable {
    case brief, standard, detailed
    public var id: String { rawValue }
    public var label: String { rawValue.capitalized }
}

public enum DescriptionStyle: String, CaseIterable, Codable, Sendable, Identifiable {
    case direct, conversational, spatial
    public var id: String { rawValue }
    public var label: String { rawValue.capitalized }
}

public struct DescriptionOptions: Equatable, Codable, Sendable {
    public var length: DescriptionLength
    public var style: DescriptionStyle
    public init(length: DescriptionLength = .standard, style: DescriptionStyle = .direct) {
        self.length = length
        self.style = style
    }
}
