# App-specific R8 rules. There are none yet, deliberately.
#
# kotlinx.serialization ships its own rules (META-INF/com.android.tools/r8/ in
# kotlinx-serialization-core), and they cover everything this app serializes: the Companion
# serializer() and object INSTANCE lookups that type-safe navigation does for Route and
# MatchSource, and the generated $$serializer classes for MatchMode and the saved snapshot. Serial
# names are string constants written in at compile time, so renaming classes changes nothing
# on the wire.
#
# The engine's codecs (GameStateCodec, MatchCodec) build and read JsonElement trees by hand
# and write enums by their rawValue, so they use no reflection and need no rules either.
#
# Check a release build after changing any of this: app/build/outputs/mapping/release/ shows
# what R8 kept, renamed and removed.
