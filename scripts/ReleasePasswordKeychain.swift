import Foundation
import Security

private let service = "io.github.parryqiu.android-imessage-relay.release-signing"
private let account = "keystore-password"

private func fail(_ message: String, status: OSStatus? = nil) -> Never {
    let suffix = status.map { " status=\($0)" } ?? ""
    FileHandle.standardError.write(Data("error=\(message)\(suffix)\n".utf8))
    exit(1)
}

private let query: [CFString: Any] = [
    kSecClass: kSecClassGenericPassword,
    kSecAttrService: service,
    kSecAttrAccount: account,
]

guard CommandLine.arguments.count == 2 else { fail("expected_command") }
if CommandLine.arguments[1] == "get" {
    var readQuery = query
    readQuery[kSecReturnData] = true
    readQuery[kSecMatchLimit] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(readQuery as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else {
        fail("keychain_read_failed", status: status)
    }
    FileHandle.standardOutput.write(data)
    exit(0)
}

guard CommandLine.arguments[1] == "set" else { fail("invalid_command") }
let password = FileHandle.standardInput.readDataToEndOfFile()
guard password.count >= 32 && password.count <= 256 else { fail("invalid_password") }
let update: [CFString: Any] = [
    kSecValueData: password,
    kSecAttrLabel: "Android iMessage Relay release signing password",
]
var status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
if status == errSecItemNotFound {
    var add = query
    update.forEach { add[$0.key] = $0.value }
    status = SecItemAdd(add as CFDictionary, nil)
}
guard status == errSecSuccess else { fail("keychain_write_failed", status: status) }
print("release_password_keychain=stored")
