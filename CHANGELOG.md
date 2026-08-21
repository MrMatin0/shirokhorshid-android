# Changelog

Notable changes to Shir o Khorshid. Versions are CalVer (`YYYY.MM.DD`) and match
`VERSION_NAME` in `version.properties`; the release workflow reads the section
for the version it is building and uses it as the release notes.

## 2026.08.21

First release of the fork with a working build, a real design system and a
version of record.

### Interface

- Redesigned Home, Statistics and Logs tabs on a Material 3 dark theme built
  from the Lion & Sun palette.
- Connection state is now readable as text ("Connected", "Connecting",
  "Waiting for network", "Not connected") with a supporting hint, instead of
  being signalled only by the emblem's opacity.
- The emblem is a control: tapping it connects or disconnects.
- New live session card on the Home tab showing duration, data sent and data
  received while the tunnel is up.
- LAN proxy sharing details are tappable to copy: address, port and credentials.
- Log lines can be long-pressed to copy, which is what anybody diagnosing a
  connection problem needs.
- The Logs tab has an empty state instead of a blank screen.
- Clearing the log asks for confirmation and confirms afterwards.
- Statistics tab shows session, sent and received as three cards with
  colour-coded totals and captioned graphs.
- Accessibility: the emblem announces what it will do, and connection state
  changes are announced as they happen.

### Fixes

- The release build could not get through Gradle configuration: `compileSdk`
  was declared inside `defaultConfig`, and `ndkVersion` was pinned to an NDK no
  CI image ships. `minSdkVersion` moves to 21 as a result.
- Deep links were checked against the `psiphon` scheme while the manifest only
  registers `shirokhorshid`, so every deep link was silently dropped, and a
  scheme-only URI threw a `NullPointerException`.
- The VPN data collection disclosure was re-shown on every resume even after
  it had been accepted.
- The waiting-for-network animation and the emblem pulse both ran continuously,
  regardless of whether they were on screen or the app was even in the
  foreground.
- The toolbar menu was read by index and dereferenced without null checks.
- Malformed diagnostic log entries left their row unbound, so the log showed a
  recycled line that was never logged.
- The Statistics tab kept redrawing its charts while off screen and mismatched
  its subscription lifecycle with the fragment's view lifecycle.
- Theme attributes defined in `values/` were silently dropped on Android 15+
  because `values-v35` re-declared both themes from scratch.
- `ViewPager` was measured with `wrap_content` inside a `match_parent` parent,
  under-measuring page content.

### Build and release

- Go 1.26.3 for the tunnel-core AAR (vendored `psiphon-tls` needs
  `crypto/hpke`).
- The NDK the APK build needs is installed explicitly instead of relying on
  whatever the CI image happens to contain.
- `version.properties` carries `VERSION_NAME` and `VERSION_CODE`; the version
  is resolved once per run and shared between the build and release jobs, so
  the APK, the tag, the title and the asset filename cannot disagree.
- Release notes are generated from this file.
