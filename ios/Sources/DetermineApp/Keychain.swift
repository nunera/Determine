#if canImport(SwiftUI)
import Foundation
import Security

/// Generic-password Keychain items for secrets (the describe-api bearer key). Stored on this device only, readable
/// while it is unlocked, never synced or backed up.
enum Keychain {
    private static let service = "org.determine.prototype"

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static func get(_ account: String) -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data,
              let value = String(data: data, encoding: .utf8), !value.isEmpty
        else { return nil }
        return value
    }

    /// `nil` deletes the item.
    static func set(_ value: String?, account: String) {
        SecItemDelete(query(account) as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }
        var q = query(account)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
#endif
