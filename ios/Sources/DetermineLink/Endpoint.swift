import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

/// An IPv4 address + UDP port. The wire is IPv4-only (the boards and the phone hotspot are IPv4 LANs).
public struct Endpoint: Hashable, Sendable, CustomStringConvertible {
    /// Host-order IPv4 address (`192.168.1.2` → `0xC0A80102`).
    public let ip: UInt32
    public let port: UInt16

    public init(ip: UInt32, port: UInt16) {
        self.ip = ip
        self.port = port
    }

    public func with(port: UInt16) -> Endpoint { Endpoint(ip: ip, port: port) }

    public var ipString: String {
        "\(ip >> 24 & 0xFF).\(ip >> 16 & 0xFF).\(ip >> 8 & 0xFF).\(ip & 0xFF)"
    }

    public var description: String { "\(ipString):\(port)" }

    /// Resolves an IPv4 literal or host name (`getaddrinfo`, AF_INET only). Returns nil when it does not resolve.
    public static func resolve(host: String, port: UInt16) -> Endpoint? {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        #if canImport(Glibc)
        hints.ai_socktype = Int32(SOCK_DGRAM.rawValue)
        #else
        hints.ai_socktype = SOCK_DGRAM
        #endif
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &res) == 0, let first = res else { return nil }
        defer { freeaddrinfo(res) }
        guard let sa = first.pointee.ai_addr else { return nil }
        let sin = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
        return Endpoint(ip: UInt32(bigEndian: sin.sin_addr.s_addr), port: port)
    }

    /// Parses `HOST:PORT` (last colon splits). Returns nil on a bad shape, port, or unresolvable host.
    public static func parse(_ text: String) -> Endpoint? {
        guard let idx = text.lastIndex(of: ":"), idx != text.startIndex,
              let port = UInt16(text[text.index(after: idx)...]) else { return nil }
        return resolve(host: String(text[..<idx]), port: port)
    }

    func sockAddrIn() -> sockaddr_in {
        var sin = sockaddr_in()
        #if canImport(Darwin)
        sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        sin.sin_family = sa_family_t(AF_INET)
        sin.sin_port = port.bigEndian
        sin.sin_addr.s_addr = ip.bigEndian
        return sin
    }

    init(_ sin: sockaddr_in) {
        self.init(ip: UInt32(bigEndian: sin.sin_addr.s_addr), port: UInt16(bigEndian: sin.sin_port))
    }
}

/// One of this device's IPv4 addresses, for the "enter this on the board" help screen.
public struct LocalAddress: Hashable, Sendable {
    public let interface: String
    public let address: String
}

public enum LocalAddresses {
    /// Every up, non-loopback IPv4 address on this device, e.g. `bridge100 172.20.10.1` for the iPhone Personal
    /// Hotspot. Empty when there is no usable interface.
    public static func ipv4() -> [LocalAddress] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let head = list else { return [] }
        defer { freeifaddrs(head) }
        var out: [LocalAddress] = []
        var cur: UnsafeMutablePointer<ifaddrs>? = head
        while let ifa = cur {
            defer { cur = ifa.pointee.ifa_next }
            guard let sa = ifa.pointee.ifa_addr, Int32(sa.pointee.sa_family) == AF_INET else { continue }
            let flags = UInt32(ifa.pointee.ifa_flags)
            guard flags & UInt32(IFF_UP) != 0, flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            let sin = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
            let name = String(cString: ifa.pointee.ifa_name)
            out.append(LocalAddress(interface: name, address: Endpoint(ip: UInt32(bigEndian: sin.sin_addr.s_addr), port: 0).ipString))
        }
        return out
    }
}
