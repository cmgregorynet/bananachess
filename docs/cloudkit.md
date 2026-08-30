# Live play over CloudKit — what has to be set up

CloudKit gives us both halves of "invite by link and just play": `CKShare` produces a
real share URL, and CloudKit subscriptions push the opponent's move to the other device
so it arrives on its own.

None of the code needs a server. All of it needs the App ID to carry three capabilities,
and those can only be granted from an account signed in to Xcode.

## 1. Sign in to Xcode

Xcode → Settings → Accounts → **+** → Apple ID. Sign in with the Apple ID on team
`8C6J659NC4`.

Nothing below works until this is done — and it is the same thing blocking the TestFlight
upload, which fails today with "No Accounts". One sign-in unblocks both.

## 2. Add three capabilities to the app target

Select the **BananaChess** target → **Signing & Capabilities** → **+ Capability**, three
times:

| Capability | What to set |
|---|---|
| **iCloud** | Tick **CloudKit**. Under Containers press **+** and create `iCloud.com.charlesbarton.bananachess` |
| **Push Notifications** | Nothing to configure — CloudKit subscriptions are delivered as silent pushes |
| **Background Modes** | Tick **Remote notifications** |

Xcode writes these into `BananaChess.entitlements` and enables them on the App ID at the
same time. Doing it here rather than in the developer portal keeps the two in step.

### Then flip the flag, in the same sitting

In `App/BananaChess-Info.plist`, set `BananaChessCloudKitEnabled` to `<true/>`.

This is not ceremony. `CKContainer` **traps** rather than failing when the app has no
iCloud entitlement, and iOS gives an app no supported way to read its own entitlements at
runtime — `SecTaskCopyValueForEntitlement` is macOS only. So the flag is what tells the
app whether it is allowed to touch CloudKit at all.

Which means the two settings must move together, and the failure is asymmetric:

| Entitlement | Flag | Result |
|---|---|---|
| absent | `false` | iCloud play is off; Invite by Link falls back to correspondence links |
| present | `false` | iCloud play stays off — harmless, just not switched on |
| absent | **`true`** | **the app crashes on launch**, before it draws anything |

The last row is not theoretical: it is exactly how this arrived, caught by the UI suite
when all twenty tests died at startup.

If you would rather use the portal: Certificates, Identifiers & Profiles → Identifiers →
`com.charlesbarton.bananachess` → enable **iCloud** (with the container above) and **Push
Notifications**, then let Xcode refresh the provisioning profile.

## 3. Deploy the schema before any TestFlight build

CloudKit keeps **development** and **production** environments apart, and they do not
share a schema. A debug build from Xcode talks to development; **a TestFlight build talks
to production**. A schema that only exists in development means a TestFlight build that
fails on every save.

So, once and after any change to what gets stored:

1. Run the app from Xcode and start one online match. That creates the record type in
   development automatically.
2. [CloudKit Console](https://icloud.developer.apple.com/) → container
   `iCloud.com.charlesbarton.bananachess` → **Schema** → **Deploy Schema to Production**.

This is the single most common way CloudKit apps ship broken, so it is worth doing
deliberately rather than when TestFlight complains.

## 4. On each device

Both players need to be **signed in to iCloud** — Settings → Apple ID. Nothing else; they
do not need a Game Center account, and there is no sign-in inside the app.

## 5. Still needed for TestFlight, separately

- An app record for `com.charlesbarton.bananachess` in App Store Connect.
- **Game Center** enabled on the App ID, because the app already ships that entitlement
  for the existing Play Online mode.

## What I need back from you

Just confirmation that step 2 is done and the container name matches. The container
identifier is the one thing the code has to agree with you about — everything else it
discovers at runtime.
