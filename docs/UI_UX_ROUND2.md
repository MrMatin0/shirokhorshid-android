# UI/UX round two

Follow-up to `UI_REDESIGN.md`, which introduced the design system. That pass was
about how the app looks. This one is about what it does when nobody is watching,
and about the things it refused to tell the user.

## Correction to UI_REDESIGN.md

That document lists `minSdkVersion 14` as a hard constraint and derives several
decisions from it (pinned AndroidX versions, no Compose, no ripples). The floor
is now **API 21**: the NDK that builds tun2socks does not support anything
older, so the build fix that unbroke the release raised it. The AndroidX pins
are still in place because nothing has re-evaluated them, not because API 14
requires them. `ripple` drawables are used from this pass onward.

Everything else in that document still holds.

## Bugs fixed

### Two animations that never stopped

`AnimationDrawable.start()` was called on the waiting-for-network bar once in
`MainActivity.onCreate` and never stopped. The bar is `INVISIBLE` for all but a
few seconds of a session, but the drawable kept scheduling frame callbacks for
the entire life of the activity. It now starts and stops with its own
visibility, through a single `setWaitingForNetworkIndicatorVisible()` helper, and
stops in `onStop`.

The emblem pulse `ObjectAnimator` was only cancelled in
`HomeTabFragment.onDestroy`, so it kept animating while the tab was paused or
the app was backgrounded, and held a hard reference to the emblem past
`onDestroyView`. Cancelled in `onPause` now, with every view reference released
in `onDestroyView`.

### A glow that contradicted the emblem

The radial glow was drawn at full strength in every state. Disconnected, that
put a bright gold halo behind an emblem deliberately dimmed to 30%: the two
strongest visual elements on the screen disagreed about whether the app was on.
Glow opacity now tracks tunnel state (0.16 / 0.6 / 1.0).

### Positional menu lookup

`onCreateOptionsMenu` did `menu.getItem(1).getActionView().findViewById(...)`:
two unchecked dereferences behind an index that breaks the moment the menu
gains an item. Items are looked up by id and null-guarded. The version label
was already being hidden, so the whole item is hidden instead of just its inner
`TextView`, and it stops reserving toolbar space.

### Clear logs

Irreversible, deletes every entry, sits next to Connect, and previously ran on
a single tap with no confirmation before and no feedback after. It now confirms
first and reports afterwards.

### Log row recycling

When a diagnostic entry failed to parse as JSON, `onBindViewHolder` returned
without binding the holder. The recycled row kept the text of whichever entry
it previously displayed, so the log showed a line that was never logged, next
to the wrong timestamp. Malformed entries now fall back to the raw payload and
paging placeholders are explicitly cleared.

### Statistics subscription lifecycle

The data stats subscription was created in `onViewCreated` and disposed in
`onDestroy`. Four charts kept repainting while the tab was off screen, and any
view recreation without a fragment destroy either doubled the subscription or,
after `dispose()`, silently got none at all. Moved to the `onResume`/`onPause`
pair the other tabs use.

### Empty logs tab

A fresh install, or the moment straight after Clear logs, rendered a completely
blank tab: indistinguishable from a screen that failed to load. There is an
empty state now.

### Accessibility

The emblem's content description was the static "Connect or disconnect" for a
control that does one of two opposite things, and state changes were never
announced at all. The description now follows the state, and the status line is
a polite live region so a screen reader reads transitions as they happen.

## Features added

### Live session card (Home)

Duration, sent and received, colour-coded the same way as the Statistics tab
(gold for sent, green for received), hidden unless the tunnel is connected. It
rides the existing `dataStatsFlowable`, the same periodic stream the Statistics
tab subscribes to, so there is no additional polling. "How long have I been
connected and how much have I used" no longer costs a tab switch.

### Copy affordances

The LAN proxy rows copy on tap and log rows copy on long-press. Both cases were
previously read-only text: sharing a proxy meant reading an address and password
aloud, and reporting a bug meant retyping a log line by hand.

The rows render localised labels ("HTTP proxy: 10.0.0.4:8080"), so the copy path
keeps the raw values separately rather than copying the rendered label.

### Snackbars instead of silence

`SkUi` centralises confirmation and clipboard writes:

- Snackbars resolve `R.id.main_content` from any view in the hierarchy, so they
  are laid out inside the tab content area. The default anchor is
  `android.R.id.content`, which puts the snackbar on top of the bottom action
  bar and covers the Connect button.
- They are tinted from the palette; Material's default is a light surface,
  which is jarring in a dark-only app.
- Clipboard writes guard the null service and the `setPrimaryClip` failures
  some OEM ROMs throw, and suppress our own confirmation on Android 13+ where
  the platform already shows one.

The content `FrameLayout` in `main_activity.xml` became a `CoordinatorLayout` to
support this. Same children, same gravity semantics, and the help FAB now gets
snackbar-avoidance behaviour for free.

### Haptics

A `VIRTUAL_KEY` tick when the tunnel is toggled and a `LONG_PRESS` tick when a
log line is copied. No permission required.

## Compatibility

Every view id and view type referenced from Java is unchanged.
`CoordinatorLayout` is a `ViewGroup`, so the `ViewPager` and FAB lookups hold.
The `ViewFlipper`'s first child is still `statusLayout`. `MaterialButton` is
still a `Button`. No new dependencies: `coordinatorlayout` and `Snackbar` both
arrive with `com.google.android.material`, already on the classpath.

All new strings are prefixed `sk_` and ship with Persian translations.

## Manual QA

1. Cold start: emblem dim, glow barely visible, status reads "Not connected".
2. Tap the emblem: connection starts, phone ticks, glow brightens, emblem
   pulses.
3. Connected: glow at full strength, session card appears and its duration
   counts up.
4. Background the app while connecting, then return: the pulse resumed rather
   than having run the whole time (check with the profiler if you care).
5. Airplane mode mid-connect: the animated gold bar appears; leave the state and
   confirm the bar stops animating rather than continuing invisibly.
6. Enable LAN proxy sharing: tap each row, confirm the clipboard holds the bare
   value (`10.0.0.4:8080`, not "HTTP proxy: 10.0.0.4:8080") and that the
   snackbar does not cover the Connect button.
7. Logs tab: long-press a row, confirm the ripple, the haptic and the copy.
8. Clear logs: confirm the dialog, then the empty state, then the snackbar.
9. Rotate on every tab; nothing duplicates and no charts stack up.
10. TalkBack: connect and confirm the state change is announced, and that the
    emblem reads "Disconnect" once connected.
11. Persian: RTL log rows mirror, the session card does not overflow, the
    clear-logs dialog is translated.
12. Android 15+ device: no edge-to-edge regression.
