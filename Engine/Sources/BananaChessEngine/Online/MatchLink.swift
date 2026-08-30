import Foundation

/// A whole match, in a URL.
///
/// This works because a `MatchPayload` is already the entire truth of a match — versioned,
/// self-contained, and refused outright if it does not match this build. Nothing has to be
/// stored anywhere: the link *is* the game, so two people can play across any messaging
/// app with no server, no accounts and no matchmaking.
///
/// Measured sizes, compressed and url-safe: a fresh match is about 710 characters, thirty
/// turns in about 1,600, eighty turns about 2,700. Long for a link, short for a message.
public enum MatchLink {
    /// Bumped if the *encoding* changes, independently of the payload's own schema. A
    /// reader that cannot read the wrapper should say so rather than mangle the contents.
    public static let format = 1

    public static let path = "m"
    /// Works with no domain and no hosting, which is what makes link play testable today
    /// and usable before an associated domain exists.
    public static let scheme = "bananachess"

    public enum LinkError: Error, Equatable, LocalizedError {
        case notAMatchLink
        case unsupportedFormat(found: Int, supported: Int)
        case unreadable

        public var errorDescription: String? {
            switch self {
            case .notAMatchLink: "That link isn't a Banana Chess match."
            case .unsupportedFormat: "That match was sent from a newer version of Banana Chess."
            case .unreadable: "That match link is damaged and can't be read."
            }
        }
    }

    /// A universal link, when the app has an associated domain to hand.
    public static func url(for payload: MatchPayload, host: String) throws -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/\(path)/\(format)/\(try encode(payload))"
        guard let url = components.url else { throw LinkError.unreadable }
        return url
    }

    /// The scheme-only form. Needs nothing hosted anywhere, so it works the moment the app
    /// is installed — which is the whole requirement when the recipient already has it.
    public static func url(for payload: MatchPayload) throws -> URL {
        guard let url = URL(string: "\(scheme)://\(path)/\(format)/\(try encode(payload))") else {
            throw LinkError.unreadable
        }
        return url
    }

    /// Reads either form. The payload's own version guards still apply on top of this: a
    /// link from an older rules build decodes here and is then refused by `MatchCodec`,
    /// which is the correct order — a readable link that cannot be played is a clearer
    /// failure than an unreadable one.
    public static func payload(from url: URL) throws -> MatchPayload {
        var parts = url.path.split(separator: "/").map(String.init)
        // The scheme form puts the first path element in `host`.
        if url.scheme == scheme, let host = url.host { parts.insert(host, at: 0) }
        guard parts.count == 3, parts[0] == path else { throw LinkError.notAMatchLink }
        guard let sent = Int(parts[1]) else { throw LinkError.notAMatchLink }
        guard sent <= format else {
            throw LinkError.unsupportedFormat(found: sent, supported: format)
        }
        return try decode(parts[2])
    }

    /// Compressed before encoding, because the payload is JSON and JSON of this shape is
    /// mostly repeated key names — it compresses to roughly a fifth.
    static func encode(_ payload: MatchPayload) throws -> String {
        let json = try MatchCodec.encode(payload)
        guard let compressed = try? (json as NSData).compressed(using: .zlib) as Data else {
            throw LinkError.unreadable
        }
        return compressed.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ text: String) throws -> MatchPayload {
        var base64 = text
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        // Base64 wants its padding back before it will decode.
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }

        guard let compressed = Data(base64Encoded: base64),
              let json = try? (compressed as NSData).decompressed(using: .zlib) as Data
        else { throw LinkError.unreadable }
        return try MatchCodec.decode(json)
    }
}
