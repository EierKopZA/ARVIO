## Fix: Collections row not scrolling into view on Android TV details page

### Problem
When navigating down to the Collections row on the details page via D-pad, the collection items were not visible. The user could see that focus was moving (pressing Enter would open the hidden-but-focused card), but the `TvLazyColumn` never scrolled to bring the collection row into view.

### Root Cause
A **race condition** in the `LaunchedEffect` scroll handler at [`DetailsScreen.kt:2094`](https://github.com/ProdigyV21/ARVIO/blob/main/app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsScreen.kt#L2094).

The collection items load **asynchronously** - `DetailsViewModel.loadDetails()` calls `getMovieCollectionRef()` then `getTmdbCollectionItems()` in separate coroutines. When the user navigates down past the reviews section **before** the collection API responds:

1. `hasCollection = false` (items haven't loaded yet) → `collectionIdx = -1`
2. `LaunchedEffect(focusedSection, contentHasFocus)` fires with `focusedSection = COLLECTION`
3. `targetIndex = collectionIdx = -1` → early return at `if (targetIndex < 0) return@LaunchedEffect` → **no scroll happens**
4. API responds → `hasCollection = true` → `collectionIdx = validIndex`
5. But the `LaunchedEffect` keys (`focusedSection`, `contentHasFocus`) **haven't changed** → effect never re-fires
6. User sees reviews screen still visible, but D-pad focus is actually on the invisible collection row

The same issue potentially affects the "More like this" (SIMILAR) section which also loads with a 320ms delay.

### Fix
Added `hasCollection` and `hasSimilar` as keys to the `LaunchedEffect`. Now when async data loads for either section and the boolean transitions from `false` to `true`, the effect re-fires and correctly scrolls the `TvLazyColumn` to the section if the user has already navigated there.

### Changes
- **`DetailsScreen.kt`**: Changed `LaunchedEffect(focusedSection, contentHasFocus)` → `LaunchedEffect(focusedSection, contentHasFocus, hasCollection, hasSimilar)`
