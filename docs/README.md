# Universal links for match sharing

Link play works today without any of this: the app registers the `bananachess://`
scheme, so a shared link opens the app on any device that has it installed. This
folder is the upgrade to universal links — `https://…` links that open the app
directly from Messages rather than showing a scheme URL.

Three things have to line up, and none of them can be done from this repository
alone:

1. **Host this folder over HTTPS.** GitHub Pages will do it: repository Settings →
   Pages → Source `main`, folder `/docs`. The file must end up at
   `https://<domain>/.well-known/apple-app-site-association`, served over HTTPS
   with no redirect. Apple fetches it once at install; it does not need a
   `.json` extension and must not have one.

2. **Add the entitlement.** In `App/BananaChess.entitlements`:

   ```xml
   <key>com.apple.developer.associated-domains</key>
   <array>
       <string>applinks:&lt;domain&gt;</string>
   </array>
   ```

   The App ID also needs the Associated Domains capability enabled in the
   developer portal, the same way Game Center does.

3. **Point the app at the domain.** Set `MatchLinkSettings.associatedDomain` to
   the same host. That is the only code change; the link format is already
   `/m/<format>/<payload>`, which is what the `components` entry above matches.

`appIDs` is `<team>.<bundle id>`. If either changes, this file changes with it.
