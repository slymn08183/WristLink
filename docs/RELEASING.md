# Releasing WristLink

One-time setup, then every release after is `git tag vX.Y.Z && git push --tags`.

## One-time: generate the release keystore

The keystore is the project's signing identity. Treat it like a private key — if you lose it, you can never publish updates that install over the existing app (users would have to uninstall first).

```bash
keytool -genkey -v \
  -keystore wristlink-release.keystore \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias wristlink
```

When prompted:
- **Keystore password:** strong, unique. Save it.
- **Distinguished name:** anything reasonable (`CN=WristLink, O=WristLink, C=US`).
- **Key password:** same as keystore password (simpler).

**Back up the `.keystore` file** somewhere safe — 1Password attachment, encrypted disk, etc. Don't commit it. The repo's `.gitignore` already blocks `*.keystore`.

## One-time: add four GitHub Secrets

GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**. Add all four:

| Secret name         | Value                                                              |
| ------------------- | ------------------------------------------------------------------ |
| `KEYSTORE_BASE64`   | Output of `base64 -i wristlink-release.keystore` (one long line) |
| `KEYSTORE_PASSWORD` | The keystore password from `keytool`                               |
| `KEY_ALIAS`         | `wristlink`                                                      |
| `KEY_PASSWORD`      | The key password (same as keystore password if you reused it)      |

To copy the base64 to your clipboard on macOS:
```bash
base64 -i wristlink-release.keystore | pbcopy
```

Or set all four with the GitHub CLI from Windows PowerShell, with a `keystore.env` holding
`KEYSTORE_PATH=`, `KEYSTORE_PASSWORD=`, `KEY_ALIAS=` and `KEY_PASSWORD=` lines:

```powershell
Get-Content ..\keys\keystore.env | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { Set-Item "env:$($matches[1].Trim())" $matches[2].Trim() } }
[Convert]::ToBase64String([IO.File]::ReadAllBytes($env:KEYSTORE_PATH)) | gh secret set KEYSTORE_BASE64
$env:KEYSTORE_PASSWORD | gh secret set KEYSTORE_PASSWORD; $env:KEY_ALIAS | gh secret set KEY_ALIAS; $env:KEY_PASSWORD | gh secret set KEY_PASSWORD
```

## One-time: releasing from a fork

GitHub doesn't copy secrets to forks and keeps Actions off until you turn them on:

1. Fork → **Actions** tab → **I understand my workflows, go ahead and enable them**.
2. Create your own keystore and add the four secrets above to the fork.
3. **Actions → Release → Run workflow** (branch `master`) to cut the first release without another push.

Without the secrets the run stops at *Decode signing keystore* with a clear error, and no tag is created.

## Cutting a release

Releases are **automatic on every push to `master`** that touches app code. No manual tagging needed:

```bash
git add -A && git commit -m "feat: my change"
git push origin master
```

The `Release` workflow (`.github/workflows/release.yml`) will:
1. Look at the latest `v*` tag and compute the next patch version (e.g. `v0.1.0` → `v0.1.1`).
2. Push the new tag.
3. Build a signed release APK (`assembleRelease`).
4. Verify the signature with `apksigner`.
5. Create a GitHub Release named `WristLink 0.1.1` with auto-generated notes from commits/PRs since the previous tag.
6. Attach `wristlink-0.1.1.apk` + `install.sh` + `install.bat`.

Watch the run at **GitHub repo → Actions tab**. Total time ~3 minutes.

### Path filter — what triggers a release

Only changes under these paths trigger a release:
- `app/**` — Kotlin source, manifest, resources
- `gradle/**` — version catalog, wrapper
- `build.gradle.kts` — root build script
- `settings.gradle.kts`
- `.github/workflows/release.yml` — workflow itself

Pushes that only touch `README.md`, `docs/`, `scripts/`, `LICENSE`, etc. are **skipped**. This keeps the release stream focused on actual app changes.

### Manual minor / major bumps

Auto-bump always increments the patch number. To release a minor or major version, tag the
commit yourself and push the tag with it; a commit that already has a `v*` tag is released as
exactly that version, and later pushes count up from it:

```bash
git tag -a v1.1.0 -m "Release v1.1.0"
git push origin master v1.1.0
```

WristLink started over at `v1.0.0` with the package rename (`com.watchbridge` → `com.wristlink`);
the `v0.1.x` releases were the old package and are gone.

### Skipping a release for an app-code change

If you need to push app code without a release (e.g. mid-refactor on master), include `[skip ci]` in your commit message — GitHub Actions skips the run entirely.

## Local release build (sanity check)

To test the release config without pushing a tag:

```bash
export KEYSTORE_PATH=/path/to/wristlink-release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=wristlink
export KEY_PASSWORD=...

./gradlew :app:assembleRelease

apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

If the certificate fingerprint matches the one CI prints, you're golden.

## Versioning

Both numbers come from git (`app/build.gradle.kts`), the same way locally and in CI:

- `versionCode` is the commit count (`git rev-list --count HEAD`). It grows with every commit, and
  a local build and the release of the same commit get the same number, so a release always
  installs over a local build of an older commit.
- `versionName` is the release tag without the `v` (CI passes it in as `VERSION_NAME`). A local
  build between releases is named after the last tag, e.g. `0.1.17-2-g8415fa4`; the watch's
  updater reads that as 0.1.17 and offers 0.1.18 once it's out.

`VERSION_NAME` / `VERSION_CODE` still override both.

## How the watch updates itself

**WristLink → Settings → Updates** asks GitHub for the latest release
(`/releases/latest`, so `ios-latest`, a pre-release, never counts), compares its tag with the
installed `versionName`, downloads the first `.apk` asset, checks it is signed with the same key,
and installs it with the system installer. While adb is the installer of record (right after
`install.sh` / `install.bat`), Android asks once on the watch to confirm; after WristLink
installed itself once, later updates need no tap.

## If the keystore is lost

You're stuck with a fork: every existing user must uninstall before updating to anything signed with a new key. That's permanent for the user base. Don't lose it.

If you're publishing to Play Store later, [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756) lets Google hold the key — strongly recommended for production apps to avoid this exact disaster.
