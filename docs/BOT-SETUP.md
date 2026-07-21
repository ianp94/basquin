# basquin-bot — the maintainer automation bot

`basquin-bot` is a **GitHub App** (App ID 4356540, installed only on this repo) that authors
Claude Code's branches, commits, and PRs. Its purpose is workflow hygiene, not privilege:

- **PRs are authored by the bot, not the maintainer** — so the maintainer can *review and approve*
  them normally (GitHub forbids approving your own PR). No more `--admin` bypass merges.
- **The release `pages` job pushes the Helm-repo update to protected `main` with the bot's token**
  (the bot is a `protect-main` bypass actor; the generic GitHub Actions app cannot be, on a personal
  repo).

## Permissions (least-privilege)

`contents: write` (branches/commits + the pages push), `pull_requests: write` (open PRs),
`workflows: write` (our PRs edit `.github/workflows/*`), `metadata: read`. **No** administration,
secrets, or org scopes — ruleset/secret changes still require the human maintainer.

## How tokens are minted

Installation tokens expire hourly, so they're minted on demand from the App ID + private key
([`scripts/bot/mint-token.sh`](../scripts/bot/mint-token.sh): a pure-`openssl` JWT → installation
token, no dependencies). Locally, git uses a credential helper that calls it per push; in CI, the
`release` workflow mints one from the `BOT_APP_ID` / `BOT_PRIVATE_KEY` repo secrets.

## Local setup (maintainer machine)

The private key lives at `~/.config/basquin-bot/key.pem` (chmod 600, **never committed**), with
`~/.config/basquin-bot/env` holding `APP_ID` / `APP_KEY` / `REPO`. Git is configured repo-locally to
commit as the bot and to use the credential helper:

```bash
git config user.name  "basquin-bot[bot]"
git config user.email "307641014+basquin-bot[bot]@users.noreply.github.com"
git config credential.helper ""                       # reset inherited PAT for this repo
git config credential.https://github.com.helper ~/.config/basquin-bot/credential-helper.sh
```

## Rotating the key

Generate a new private key on the app's settings page, replace `~/.config/basquin-bot/key.pem` and
the `BOT_PRIVATE_KEY` secret, and delete the old key from the app. The App ID never changes.
