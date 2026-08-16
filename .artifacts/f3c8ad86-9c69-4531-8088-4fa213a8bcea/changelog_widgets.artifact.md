# Changelog - Music Widget

This document summarizes the technical and aesthetic improvements recently implemented in the lyrics system, power management, and widget design.

## 🏗️ Architecture & Compatibility [v1.2.0]

### Fixed
- **Critical Inflation Error (4x2)**: Fixed the "Could not add widget" error in the selector by horizontally redesigning `widget_preview_large.xml`, preventing `RemoteViews` overflow.
- **Component Incompatibility**: Replaced `<View />` tags with `<ImageView />` in all skeleton files, as the base `View` class is not compatible with the Android widget engine.
- **Icon Clipping**: Adjusted the positioning of the visualizer/history indicator (from -8dp negative margin to 3dp positive) to prevent clipping in system rendering.
- **Placeholder Flicker**: Eliminated fake data flickering when adding widgets by implementing specific loading `initialLayouts`.

### Implemented
- **Version Segmentation (Dual-Preview)**:
    - `layout-v31/`: "Rich" previews with placeholder text for Android 12+.
    - `layout/`: "Skeleton" previews for Android Legacy and initial loading state.
- **Glance Loading Skeletons**: New layouts `glance_loading_large.xml`, `standard.xml`, and `small.xml` for a smooth transition during Glance initialization.
- **Legacy Vector Skeletons**: Created `layer-list` files in `drawable/` (`preview_skeleton_*_legacy.xml`) to ensure geometric previews on Android 8 to 11.
- **Safe Dimensions Infrastructure**: Implemented `dimens.xml` (base vs v31) to handle corner radius compatibly with older APIs.

### Improved
- **Color Cohesion**: Synchronized color tokens in `colors.xml` to ensure absolute parity between the widget selector and the homescreen across light/dark and dynamic themes (Material You).
- **Live Preview Stability**: Optimized `providePreview` in Glance to skip loading heavy history bitmaps, improving performance in the Android 15+ selector.
- **Metadata Resilience**: Updated all `appwidget-provider` files with dual attributes (`previewLayout` and `previewImage`).

## 🎵 Lyrics Showcase

### "Infallible" Precision
- **Live State Reading**: Refactored the lyrics engine to query `PlaybackState` directly from the system every 500ms.
- **Time Synchronization**: Implemented conditional math based on `SystemClock.elapsedRealtime()`. Lyric progress now freezes instantly on pause and resumes without "jumps" on play.
- **Seek Interception**: Proactive detection of progress bar jumps (>2 seconds). The widget now reacts instantly to manual position changes without waiting for the next update cycle.

### Robustness & Networking
- **API Debouncing**: Added a 500ms delay before calling the lyrics API to prevent network saturation during rapid song changes.
- **Silent Fallback**: If no lyrics are found for a track (e.g., instrumentals), the system now clears the widget immediately and stops residual processes.

## 🔋 Power Optimization

- **Smart Pause**: The system detects if music has been paused for more than **2 hours**.
- **Auto-Shutdown**: After the 2-hour threshold, the Service destroys the lyric update loop to maximize battery savings.
- **Automatic History Mode**: The widget visually transitions to "History" mode (showing relative time) after a prolonged pause, even if the session is technically still active.

## 🎨 UI & Design Refinements

### Layout Unification (2x1 and 2x2)
- **Strict History Mode**: In 2x1 and 2x2 sizes, the artist name now automatically hides to show "Recently" or "X hours ago" when the session ends or is paused for a long time.
- **Aesthetic Cleanup**: Removed redundant text in the top corner of the 2x2 size to maintain a minimalist aesthetic consistent with the 2x1.

### Large Mode Improvements (4x2 / 4x4)
- **Persistent Identity**: In large widgets, the artist and lyrics are always visible, while the status remains fixed in the top right corner.
- **Fixed History Header**: Refactored the history list. The header ("RECENT HISTORY") and the clear button are now fixed at the top and do not disappear when scrolling through songs.

### Previews & Branding
- **Live Previews**: Optimized `providePreview` for faster rendering in the widget selector (skipping heavy history loading).
- **Iconography**: Complete update of app icons (Foreground, Monochrome, and Background) with a new musical note design and blue/white color palette.
- **XML Typography**: Updated all XML preview files (`16sp` for titles, `14sp` for artists) for total parity with the real widget.

## 🛠️ Technical Improvements
- **Reactive DataStore**: Centralized logic in a "Single Source of Truth" ensuring all widget sizes react consistently to the same events.
- **Atomic Pipeline**: Ensured separation of the "Visual Way" (cover/titles) from the "Lyrics Way," guaranteeing the widget never freezes waiting for an internet response.
