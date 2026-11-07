AndroidHarness 2.9 — signed release build.

Built from source at `main` (82b7137) directly on an Android device using the project's own Gradle toolchain (Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.10). Signed with a dedicated release key (V3 signature scheme).

## Highlights

- **Provider connection controls**: up to 20 named API keys per provider with rotation, per-key switches, and proxy support (HTTP/SOCKS5, optional credentials, global proxy)
- **FTP and SFTP sync**: sync a local workspace with a saved remote connection, with previews and explicit approval for replacements
- **FTP sharing**: share one workspace with an FTP client in the background (read-only or read/write)
- **Update install methods**: Automatic, Root, Shizuku or the Android installer
- **Memory sections** (Chat / Code / Shared) and global Chat instructions up to 4,000 characters
- **Workspace file links** in answers open in the editor, with an optional `:line` suffix
- **Stop for running commands**: the run button becomes Stop and Ctrl+C interrupts via hardware keyboard
- **New languages**: Persian (فارسی), Brazilian Portuguese, German, Spanish and Japanese, each listed under its own name

## Install

- Requires Android 8.0+ (minSdk 26), targetSdk 36, compileSdk 37
- Install the APK directly (allow installs from this source when asked)
- This build was produced without the NDK, so the optional native library `libharness_local.so` is not included; all other functionality is unchanged
- The debug-signed CI artifact (Actions tab) is unaffected; this release APK uses a dedicated release key

## Verification

- APK: `AndroidHarness-2.9-release.apk` (37,284,443 bytes)
- SHA-256: `66238a01c1cb49db88adb7a144bf09ac12bf3da21eaa801a6324128538718d92`
- Signer: `CN=AndroidHarness Release, O=adel107107ai` (V3 scheme)
