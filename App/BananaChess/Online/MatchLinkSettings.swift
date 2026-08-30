import BananaChessEngine
import Foundation

/// Where a shared match link points.
///
/// Set `associatedDomain` once a domain is serving `apple-app-site-association` and the
/// app carries a matching `applinks:` entitlement, and every link becomes a universal
/// link that opens the app straight from Messages. Until then links use the app's own
/// scheme, which needs nothing hosted and works the moment the app is installed — which
/// is the whole requirement when the recipient already has it.
enum MatchLinkSettings {
    static let associatedDomain: String? = nil

    static func url(for payload: MatchPayload) throws -> URL {
        if let associatedDomain {
            return try MatchLink.url(for: payload, host: associatedDomain)
        }
        return try MatchLink.url(for: payload)
    }
}
