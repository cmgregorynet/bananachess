# Hosting the web version

Short version: it runs on Cloudflare's network, not on your machine, and it can be
deployed without your machine either.

## Where it actually runs

`wrangler deploy` uploads the Worker to Cloudflare's edge. From that moment the site and
the match rooms live there — your laptop can be closed, asleep, or in a different
country. The only thing a local deploy does is build the files and push them up.

(`wrangler dev` is the one that runs locally, and only for development.)

## Deploying without your machine at all

`.github/workflows/web.yml` runs the tests, the typechecks and the build on GitHub's
runners, and deploys from there on every push to `main` that touches `Web/`. Two
repository secrets are needed:

| Secret | Where it comes from |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare dashboard → My Profile → API Tokens → Create Token → **Edit Cloudflare Workers** template |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare dashboard → Workers & Pages, in the right-hand sidebar |

Add them under repository **Settings → Secrets and variables → Actions**.

After that, pushing a change to `Web/` publishes it. No local Node, no local wrangler, no
`wrangler login`.

Note the conformance fixtures are committed to the repository, so the rules are checked
against the Swift engine on a plain Linux runner — no macOS machine and no Swift
toolchain needed in CI.

## The first deploy

The very first deploy still has to create the Worker and its Durable Object namespace.
Either run it once locally:

```sh
cd Web && npx wrangler login && npm run deploy
```

…or just add the two secrets and push — the Actions run will create them the same way.

## What this does *not* cover

The iOS app. Archiving and uploading to TestFlight needs Xcode, a signed-in Apple ID and
the signing certificates, so that stays on your machine.
