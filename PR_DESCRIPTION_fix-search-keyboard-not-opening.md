## Title
fix: enable keyboard on search box OK press for Android TV

## Body

### Issue
On Android TV, when focusing the search box and pressing OK/Enter to start typing, the on-screen keyboard never appears. The search box appears to "enter edit mode" but no keyboard shows and no text input is possible.

### Root Cause
The `BasicTextField` was conditionally rendered inside the `SearchInputBar` composable — it only entered the composition tree when `isEditing` became `true`. Simultaneously, a `LaunchedEffect(isSearchEditing)` fired to request focus on `textInputFocusRequester` and show the keyboard via `keyboardController?.show()`. However, the `BasicTextField` (and its associated `FocusRequester`) hadn't been composed or laid out yet, so both operations silently targeted a non-existent composable. On TV hardware with slower frame rendering, this race condition consistently prevented the keyboard from opening.

### Fix
Replaced the conditional `if (isEditing) { BasicTextField(...) } else { Text(...) }` block with an always-present `BasicTextField` that uses `readOnly = !isEditing`:

- **Not editing:** `readOnly = true` — displays as static text (same visual as the previous `Text` fallback, via `decorationBox`)
- **Editing:** `readOnly = false` — `textInputFocusRequester` is already laid out and ready, so focus and keyboard requests succeed immediately

### File Changed
`app/src/main/kotlin/com/arflix/tv/ui/screens/search/SearchScreen.kt` — `SearchInputBar` composable (line 666)

### Testing
- `compileSideloadDebugKotlin` — passes
- D-pad focus navigation between search bar, filters, and results remains unaffected
- Back/Escape while editing exits edit mode and returns focus to the search bar properly
