## Fix: Similar/Collection poster cards cut off at bottom on TV details page

### Problem
On 1080p Android TVs, "More Like This" and Collection poster cards had their bottom portion (including subtitle text) clipped off. The user could see the card image but the title/subtitle text below was invisible.

### Root Cause
[`contentRowHeight`](https://github.com/ProdigyV21/ARVIO/blob/main/app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsScreen.kt#L1737) was calculated as `(screenHeight * 0.34).coerceIn(240.dp, 320.dp)`. On 1080p TVs, screen density calculations caused this to hit the **240dp minimum**.

Poster-mode cards (used by both `DetailsSimilarRail` and `DetailsCollectionRail`) have these height components:
- Image area: 126dp × (3/2 aspect ratio) = **189dp**
- Title + subtitle text area: **~36dp**
- Focus bleed (TvLazyRow top + bottom padding): **36dp**
- Section title text ("More Like This" / "{name} Collection"): **~24dp**
- **Total: ~285dp** — exceeding 240dp

Since `TvLazyColumn` has `.clipToBounds()`, the bottom ~45dp of each row (including card subtitle text) was clipped.

### Fix
Increased `contentRowHeight` minimum from `240.dp` to `290.dp` (and max from `320.dp` to `360.dp`) to accommodate the tallest row items.

### Changes
- **`DetailsScreen.kt`**: Changed `coerceIn(240.dp, 320.dp)` → `coerceIn(290.dp, 360.dp)` with explanatory comment
