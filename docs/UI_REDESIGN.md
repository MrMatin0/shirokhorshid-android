# UI/UX modernization

This document describes the design system introduced in the UI modernization
pass, why it was built the way it was, and how to review it.

## Constraints that shaped the approach

A few properties of this codebase ruled out the obvious "rewrite it in Compose"
answer:

| Constraint | Consequence |
| --- | --- |
| `minSdkVersion 14` | Jetpack Compose needs API 21+. AndroidX versions are already pinned to the last releases that support API 14 (see the comment block in the root `build.gradle`). |
| 100% Java, XML view system | No Kotlin plugin is configured, and adding one plus Compose would change the build graph, the NDK/tun2socks wiring and the release pipeline. |
| `findViewById` contracts | Fragments and activities look views up by id and cast them (`ScrollView statusLayout`, `LinearLayout lanProxyInfoSection`, `ViewGroup connectionWaitingNetworkIndicator`, whose background is cast to `AnimationDrawable`). Ids and view types had to be preserved. |
| Release builds need secrets | CI builds the tunnel-core AAR, fetches server entries and signs the APK, so a PR cannot be verified by CI. Every change had to be conservative enough to be correct by construction. |

So the work is a **visual and interaction redesign on top of the existing view
system**: a real design system, Material 3 theming, and restructured layouts,
with the smallest possible Java surface area touched.

## Design system

All new resources are prefixed `sk_` so they cannot collide with resources
coming from `ca.psiphon.aar`, the `tray` module or AndroidX.

### Colour

The palette is derived from the Lion & Sun emblem the app is named after.

| Role | Token | Value |
| --- | --- | --- |
| Brand / primary | `sk_gold` | `#F2B233` |
| Connected / secondary | `sk_green` | `#34C48C` |
| Error / tertiary | `sk_crimson` | `#E05252` |
| Window background | `sk_night_900` | `#0A0D12` |
| Card surface | `sk_night_800` | `#111620` |
| Recessed surface | `sk_night_700` | `#171E29` |
| Hairline | `sk_outline_variant` | `#212B37` |
| Text primary / secondary / tertiary | `sk_text_*` | `#EDF2F8` / `#A3B0C0` / `#6D798A` |

Surfaces are a layered near-black stack rather than one flat grey, so cards
read as elevated without shadows — which matters on OLED phones and keeps the
app visually quiet.

The app stays **dark-only on purpose**. It was already an AppCompat dark theme,
and several third-party layouts inherited from the upstream Psiphon client
hard-code light-on-dark colours. Shipping a day/night theme would have quietly
broken those screens in light mode.

### Spacing and shape

`sk_space_xs` … `sk_space_xxl` on a 4dp baseline, plus `sk_radius_small`
(10dp), `sk_radius_card` (20dp) and `sk_radius_pill` (28dp, exactly half the
56dp action-button height so the primary button is a true pill).

### Theme structure

```
Base.Theme.ShirOKhorshid          (Theme.Material3.Dark + every design token)
  └── Base.Theme.ShirOKhorshid.Compat   (overridden in values-v35 only)
        └── Theme.Psiphon                (application / preference activities)
              └── Theme.Psiphon.NoActionBar   (MainActivity)
Theme.DialogAlert                  (Theme.Material3.Dark.Dialog.Alert + tokens)
```

`Theme.Psiphon` and `Theme.Psiphon.NoActionBar` keep their historical names
because `AndroidManifest.xml` references them.

The old `values-v35/styles.xml` re-declared both themes from scratch just to
add `android:windowOptOutEdgeToEdgeEnforcement`. That silently dropped every
attribute defined in `values/` on Android 15+. The opt-out now lives in the
thin `.Compat` layer, so the platform override inherits the palette instead of
replacing it. **This was a latent bug, not just a style cleanup.**

`preferenceTheme` is now declared explicitly rather than relying on
`PreferenceFragmentCompat`'s internal fallback.

## What changed per screen

### App shell (`main_activity.xml`)

- `MaterialToolbar` with a hairline separator instead of a solid colour block.
- Tab strip moved out of the content `FrameLayout`, with a rounded, non
  full-width gold indicator and explicit selected/unselected text colours.
- **Bug fix:** the `ViewPager` was `layout_height="wrap_content"` inside a
  `match_parent` parent, so page content was under-measured. It now fills the
  free space via `0dp` + `layout_weight`.
- Bottom action bar: a 56dp pill-shaped filled primary button and an outlined
  secondary button, both single-line and ellipsized so long translations (fa,
  ar) degrade instead of overflowing.
- The connect progress bar and the waiting-for-network animation now share one
  6dp slot above the buttons instead of being absolutely positioned over their
  top edge.
- Buttons are disabled-aware: `sk_btn_primary_bg` / `sk_btn_primary_fg` are
  colour state lists, so the "Waiting" state actually looks disabled. Baked-in
  alpha values are used instead of `android:alpha` on selector items, which is
  not honoured on older platforms.

### Home tab (`home_tab_layout.xml`)

- Hero block: the emblem sits on a soft radial glow.
- **The emblem is now a control.** It was an `ImageButton` with no click
  listener; tapping the largest thing on the screen did nothing. It now
  delegates to the Connect button, which already owns tunnel start/stop and
  its proxy-settings validation.
- **Connection state is now readable as text.** Previously the only signal was
  the emblem's opacity (30% vs 100%), which is invisible to a first-time user
  and unusable for anyone with low vision. There is now a status headline and
  a supporting hint, colour-coded green / gold / grey, including a distinct
  "waiting for network" message.
- LAN proxy details and the last log line moved into cards. Every hard-coded
  hex colour (`#888888`, `#DDDDDD`, `#4CAF50`) is now a palette token.

### Statistics tab (`statistics.xml`)

- Three cards — session, sent, received — replacing stacked rows separated by
  1dip pure-white dividers.
- Totals are colour-coded (gold for sent, green for received) and the graphs
  are captioned, because "two unlabelled sparklines per direction" was not
  self-explanatory.
- Chart series and grid colours come from the palette instead of
  `Color.YELLOW` / `Color.GRAY`.

### Logs tab

- Padding and no scrollbar clutter; each entry is a rounded card with a
  monospaced, de-emphasised timestamp. The `layout-fa` and `layout-ar`
  variants were updated to match.

## Compatibility notes

- Every view id and every view **type** referenced from Java is unchanged.
  `MaterialButton` extends `Button`, and `MaterialToolbar` extends
  `androidx.appcompat.widget.Toolbar`, so existing lookups and casts hold.
- `ViewFlipper`'s first child is still the `statusLayout` `ScrollView`, which
  `HomeTabFragment` compares against to decide whether the embedded web view
  is showing.
- `connectionWaitingNetworkIndicator` is still a `LinearLayout` whose
  background is the `AnimationDrawable` animation list.
- The graph containers are still `LinearLayout`s, as `DataTransferGraph`
  requires.
- No dependency, `minSdk`, `targetSdk` or AGP changes. Material 1.11.0 is
  already on the classpath and is the last release supporting API 14; all
  Material 3 styles used here are public in that version.
- New strings ship with Persian translations.

## Manual QA checklist

1. Cold start: emblem dim, status reads "Not connected", Connect enabled.
2. Tap the emblem → connection starts (same as tapping Connect).
3. While connecting: emblem pulses, status reads "Connecting…", the 6dp
   progress bar is visible, the secondary button reads "Clear logs".
4. Enable airplane mode mid-connect: status switches to "Waiting for network"
   and the animated gold bar replaces the progress bar.
5. Connected: status green, secondary button switches to "Open browser" with
   the matching icon.
6. Turn on proxy sharing over LAN → the LAN card appears with monospaced
   addresses and credentials.
7. Statistics tab: three cards, totals update, both graph pairs render.
8. Logs tab: rounded rows, newest at the bottom, "Clear logs" empties it.
9. Settings tab and the VPN / Proxy / More Options sub-screens open and are
   legible.
10. Switch the app language to Persian: RTL log rows mirror, status strings
    are translated, buttons do not overflow.
11. Android 15+ device: no edge-to-edge regression, theme colours intact
    (this is the `values-v35` fix).
