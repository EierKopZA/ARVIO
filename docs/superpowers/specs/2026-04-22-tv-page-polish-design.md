# TV Page Polish Pass — 2026-04-22

Fixes six defects/gaps on the Live TV screen reported by the user.

## Scope

Files in play:

- [LiveTvScreen.kt](app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt)
- [CategorySidebar.kt](app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/CategorySidebar.kt)
- [EpgGrid.kt](app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt)
- [MiniPlayer.kt](app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/MiniPlayer.kt)
- New: `FullscreenHud.kt` next to the above

## Fix 1 — Sidebar country group: one-press collapse

**Symptom:** tapping an expanded country requires two presses to collapse — 1st press re-selects, 2nd collapses.

**Change** (CategorySidebar.kt:147-154): a tap on a country row always toggles `expandedCountry` for that country *and* calls `onSelect(country.id)` when collapsed → expanded. Collapsing (when already expanded) just clears `expandedCountry` without changing selection. Result: one press always opens or closes the group.

## Fix 2 — EPG cells show channel name instead of programmes

**Symptom (screenshot):** blocks under the time ruler display only the channel name (`NL | NPO2 4K`, etc.). Real EPG programmes should render with title + time window.

**Root cause:** `ProgramsRow` (EpgGrid.kt:337-362) hits the `programs.isEmpty()` fallback and renders a "NOW placeholder" block containing `channel.name`. This fallback was added so rows without EPG data still look tap-friendly, but it reads as broken EPG.

**Change:** remove the name-fallback block entirely. When `programs` is empty, render an empty row (just the striped background) — no placeholder. Channels with actual EPG continue rendering per-programme cells unchanged. A follow-up investigation (not in this spec) can look at why `state.snapshot.nowNext` is sparse for these channels, but that's orthogonal to this UI fix.

## Fix 3 — Navigation sluggishness

Two concrete causes:

**3a. Scroll-sync feedback loop.** EpgGrid.kt:108-127 mirrors the channel column and program grid with two `snapshotFlow` → `scrollToItem` effects. Each direction triggers the other on every tick, yielding redundant dispatches on DPAD travel through long lists.

**Change:** make the program grid the single leader. Keep only the `programListState → channelListState` sync; delete the reverse `LaunchedEffect`. The channel column is read-only (no scroll gestures handle it directly — its focusable rows drive it indirectly through nested focus); one-way mirroring is sufficient and kills the ping-pong.

**3b. Over-triggered filter recompute.** LiveTvScreen.kt:163-168 re-filters the entire `enriched` list whenever `recents.value` changes. Every channel tune mutates `recents`, so every channel change triggers a 50k-item scan on Default dispatcher even when the current category is unrelated to recents.

**Change:** split the trigger. Move the `recents` dependency behind an `if (selectedCategoryId == "recent") recents.value else Unit` key, so recents-mutation only invalidates the filter when the Recent category is actually shown.

## Fix 4 — First channel defaults to first favorite

**Symptom:** opening the TV page starts with whatever channel happens to be first in the filtered list.

**Change** (LiveTvScreen.kt:179-183): when `playingChannelId == null` and `filteredChannels` arrives, pick `filteredChannels.firstOrNull { it.id in favSet } ?: filteredChannels.firstOrNull()`. Favorites-first if any exist, else fall back to first filtered. `initialChannelId` (deep-link) still wins because it's the initial value of `playingChannelId`.

## Fix 5 — Premium fullscreen HUD

New composable `FullscreenHud` rendered inside the fullscreen `Box` (LiveTvScreen.kt:391-415) as an overlay above the `PlayerView`.

**Layout:**

- **Top-left chip (20dp gutter):** channel logo 40dp, `CH {number}` + channel name (24sp), quality + country/lang badges row.
- **Top-right chip:** current wall clock in large mono (`HH:mm`). (No pulsing LIVE dot — per user.)
- **Bottom card (frosted, 24dp gutter, fillMaxWidth 60%, centered):**
    - `NOW` label, `start–end` window, remaining-time badge on the right.
    - Programme title (22sp, 2 lines max).
    - One-line synopsis in dim colour.
    - Cyan progress bar (reuse `progressOf`).
    - Thin divider, then a compact `NEXT {clock}  {title}` row.
    - If `nowNext` is null, show the card with just the channel tagline "No programme data" — keep the frame so up/down zapping isn't visually abrupt.

**Behaviour:**

- Auto-hide after 5s of no key input; any DPAD key re-shows and resets the 5s timer.
- Fade in/out (tween 200ms).
- OK while fullscreen: toggle HUD visibility (does not exit fullscreen).
- Back: exits fullscreen (existing behaviour preserved).

**Colour/type:** reuse `LiveColors`/`LiveType` tokens — no new palette. Card background = `LiveColors.PanelRaised.copy(alpha = 0.85f)` over a subtle bottom gradient so it reads against bright channels.

## Fix 6 — Up/Down channel zapping in fullscreen

**Scope** (per user): cycle across **all channels** in `enrichedState.value.all`, not just the currently-filtered category. Wraps around.

**Change:** attach `onPreviewKeyEvent` to the fullscreen `Box`:

```
if (isFullScreen && ev.type == KeyDown) {
    when (ev.key) {
        DirectionUp   -> move(-1); true
        DirectionDown -> move(+1); true
        DirectionCenter, Enter -> toggleHud(); true
        else -> false
    }
}
```

`move(delta)` looks up the current index in `enrichedState.value.all`, wraps, sets `playingChannelId = list[newIdx].id`, and resets the HUD's auto-hide timer so the user sees the new channel info briefly. Existing `LaunchedEffect(currentStreamUrl)` handles ExoPlayer swap; no player changes needed.

## Non-goals

- Fixing *why* `nowNext` is empty for some channels (EPG source/parsing) — separate investigation.
- Persisting the `recents` list across app restarts — called out as follow-up in existing code comments.
- Redesigning the sidebar beyond the one-press collapse fix.
- Changing top-bar behaviour, loading pane, or search overlay.

## Testing

- **Manual on device** is the ground truth (Android TV DPAD behaviour, ExoPlayer tune speed, EPG layout at 1080p). Golden path for each fix listed below; no unit tests added since all changes are Compose-level with no extractable pure logic new to this pass.
- Build (`./gradlew :app:assembleDebug`) must stay green.
- Verify each fix on APK:
    1. Expand Netherlands → single press collapses it.
    2. Channels with EPG show programme cells; channels without EPG show empty striped row (no name-fallback block).
    3. DPAD travel through Favorites → Sports → News feels smooth; no visible stutter when changing channels quickly.
    4. Cold-launch of TV page lands on the first favorite (if any exist).
    5. Press OK on an already-playing row → fullscreen → HUD shown, fades after 5s, reappears on any key.
    6. Up/Down in fullscreen zaps to prev/next global channel and HUD refreshes.
