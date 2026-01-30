# V0 Design Prompt: NDI HX3 Receiver App

## Project Overview

Create a professional-grade NDI (Network Device Interface) video receiver application UI designed for **24.5-inch tablets** (1920×1080) used in live entertainment venues (cabarets, theaters). This is a **business/industrial application** requiring high visibility, large touch targets, and a clean dark interface.

---

## Design Requirements

### Visual Style
- **Theme**: Dark theme optimized for low-light venues
- **Background**: `#121212` (dark surface)
- **Typography**: Bold headers (28sp), clear body text (14-18sp)
- **Touch Targets**: Minimum 48×48px for all interactive elements
- **Color Palette**:
  - Primary: `#1976D2` (blue)
  - Recording/Alert: `#F44336` (red)
  - Connected/Success: `#4CAF50` (green)
  - Disconnected/Inactive: `#9E9E9E` (gray)
  - Text: `#FFFFFF` (white)
  - Semi-transparent overlay: `rgba(0, 0, 0, 0.8)`

### Layout Principles
- **Responsive**: Optimize for 1920×1080 landscape orientation
- **Spacing**: Generous padding (16-24px) for readability
- **Grid**: Use ConstraintLayout-style positioning (flexible grid)
- **Hierarchy**: Clear visual hierarchy with large headers and grouped content

---

## Screen Designs

### 1. Main Screen (NDI Source List)

**Purpose**: Discover and connect to NDI sources on the network

**Layout**:
```
┌─────────────────────────────────────────────────────────────────┐
│ 📺 NDI Receiver                                   ⚙️ Settings   │
│ Searching for NDI sources...                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 📹 FoMaKo K20UH (CAMERA1)                       Connect  │  │
│  │    1920×1080 @ 30fps                                     │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 📹 OBS Studio (PC-MAIN)                         Connect  │  │
│  │    1920×1080 @ 60fps                                     │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 📹 NDI Test Pattern                             Connect  │  │
│  │    1280×720 @ 30fps                                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│  [🔄 Refresh]                                   [📁 Recordings] │
└─────────────────────────────────────────────────────────────────┘
```

**Components**:
- **Header**: App title (left), Settings button (right)
- **Status text**: "Searching for NDI sources..." (gray, small)
- **Source cards**:
  - Camera icon + source name (bold)
  - Resolution/framerate info (gray)
  - Connect button (primary color)
- **Empty state**: "No NDI sources found" with search icon
- **Bottom actions**: Refresh button (outlined), Recordings button (filled)

---

### 2. Player Screen (Live Video Display)

**Purpose**: Display live NDI stream with recording controls and OSD

**Layout**:
```
┌─────────────────────────────────────────────────────────────────┐
│ [←] FoMaKo K20UH                         🔴 REC 00:05:23  [ℹ️]  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                                                                 │
│                         [VIDEO DISPLAY AREA]                    │
│                         (Fullscreen 16:9)                       │
│                                                                 │
│                                                                 │
│                                                                 │
│  ┌─────────────────────────┐                                   │
│  │ 1920×1080 @ 30.0fps     │                                   │
│  │ H.265 | 28.5 Mbps       │                                   │
│  └─────────────────────────┘                                   │
├─────────────────────────────────────────────────────────────────┤
│  [⏺️ Stop Recording]                            [⏹️ Disconnect] │
└─────────────────────────────────────────────────────────────────┘
```

**Components**:
- **Top Bar** (semi-transparent overlay):
  - Back button (left)
  - Source name (center-left, bold)
  - Recording indicator (red badge with timer, right)
  - Info/OSD toggle button (far right)

- **Video Display**:
  - Black background
  - Centered video with aspect ratio preserved
  - Loading spinner when connecting

- **OSD Overlay** (bottom-left, toggleable):
  - Resolution, framerate, codec
  - Bitrate (green text)
  - Semi-transparent dark background

- **Bottom Bar** (semi-transparent overlay):
  - Record button (red when recording, changes text to "Stop Recording")
  - Disconnect button (outlined, white)

- **Controls behavior**:
  - Tap screen to show/hide top and bottom bars
  - OSD can be toggled independently

---

### 3. Recordings List Screen

**Purpose**: Browse and manage recorded videos

**Layout**:
```
┌─────────────────────────────────────────────────────────────────┐
│ [←] Recordings                          3 recordings | 5.2 GB  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌────────┬──────────────────────────────────────────────────┐ │
│  │ [Thumb]│ NDI_20260130_143052.mp4                          │ │
│  │ 🎬     │ 2026/01/30 14:30 | 00:15:23 | 1.2 GB             │ │
│  │        │                                   [▶️ Play] [🗑️]  │ │
│  └────────┴──────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌────────┬──────────────────────────────────────────────────┐ │
│  │ [Thumb]│ NDI_20260130_120015.mp4                          │ │
│  │ 🎬     │ 2026/01/30 12:00 | 00:45:12 | 3.8 GB             │ │
│  │        │                                   [▶️ Play] [🗑️]  │ │
│  └────────┴──────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌────────┬──────────────────────────────────────────────────┐ │
│  │ [Thumb]│ NDI_20260129_210532.mp4                          │ │
│  │ 🎬     │ 2026/01/29 21:05 | 00:08:47 | 0.2 GB             │ │
│  │        │                                   [▶️ Play] [🗑️]  │ │
│  └────────┴──────────────────────────────────────────────────┘ │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Components**:
- **Header**:
  - Back button + "Recordings" title (left)
  - Summary info (right): "3 recordings | 5.2 GB"

- **Recording cards**:
  - Video thumbnail (left, 16:9 aspect ratio, 120×68px)
  - Filename (bold)
  - Metadata: Date, duration, file size (gray, small)
  - Action buttons: Play (filled), Delete (icon)

- **Empty state**:
  - Gray icon (gallery/film)
  - "No Recordings" (large text)
  - "Recorded videos will appear here" (hint)

---

### 4. Settings Screen

**Purpose**: Configure app behavior and preferences

**Layout**:
```
┌─────────────────────────────────────────────────────────────────┐
│ [←] Settings                                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  CONNECTION                                                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Auto-reconnect                                    [Toggle]│  │
│  │ Automatically reconnect to last NDI source on loss        │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Last connected: FoMaKo K20UH                      [Clear]│  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  DISPLAY                                                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Keep screen on                                    [Toggle]│  │
│  │ Prevent screen from turning off during playback           │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Show OSD                                          [Toggle]│  │
│  │ Display video info overlay (resolution, fps, bitrate)     │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ABOUT                                                          │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ App version: 1.0.0                                        │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ NDI® is a registered trademark of Vizrt NDI AB.          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Components**:
- **Section headers**: All caps, smaller gray text
- **Setting items**:
  - Title (bold)
  - Description (gray, smaller)
  - Toggle switch or button (right-aligned)
- **Grouped cards**: White borders, dark background, rounded corners

---

## UI States & Interactions

### Loading States
- **Searching sources**: Spinner + "Searching for NDI sources..."
- **Connecting**: Overlay with spinner + "Connecting..."
- **Loading recordings**: Spinner in center

### Error States
- **No sources found**: Empty state with refresh suggestion
- **Connection error**: Overlay with "Connection Error" + Retry button
- **Auto-reconnecting**: Show attempt counter "Auto-reconnecting... (attempt 2)"

### Recording States
- **Idle**: Button shows "⏺️ Start Recording" (gray background)
- **Recording**:
  - Button changes to "⏹️ Stop Recording" (red background)
  - Red badge in top bar: "🔴 REC 00:05:23" (timer updates every second)

### Interactive Behaviors
- **Tap video area**: Toggle controls visibility
- **Long press recording item**: Show delete confirmation dialog
- **Pull to refresh**: Source list can be refreshed with pull gesture

---

## Typography Scale

| Element | Size | Weight | Color |
|---------|------|--------|-------|
| Screen titles | 28sp | Bold | White |
| Card titles | 18sp | Bold | White |
| Body text | 14-16sp | Regular | White |
| Metadata | 14sp | Regular | Gray (#9E9E9E) |
| Buttons | 14-16sp | Medium | White |
| OSD info | 14sp | Monospace | White/Green |

---

## Component Library

### Buttons
1. **Primary Button**: Filled, blue background (#1976D2)
2. **Danger Button**: Filled, red background (#D32F2F) - for recording/delete
3. **Outlined Button**: Transparent with white border
4. **Icon Button**: 48×48px touch target, no background

### Cards
- **Rounded corners**: 8-12px border radius
- **Elevation**: Subtle shadow or border
- **Padding**: 16-20px internal spacing
- **Hover/Active**: Lighter background on interaction

### Overlays
- **Semi-transparent**: `rgba(0, 0, 0, 0.8)`
- **Blur effect**: Optional backdrop blur
- **Fade animation**: Smooth 200-300ms transition

---

## Responsive Considerations

- **Minimum screen size**: 1280×720 (should still be usable)
- **Optimal size**: 1920×1080 (design target)
- **Orientation**: Landscape primary, portrait acceptable for settings
- **Touch targets**: All buttons/controls minimum 48×48px
- **Font scaling**: Support Android's system font size settings

---

## Accessibility

- **High contrast**: White text on dark backgrounds
- **Clear labels**: All icons have text labels or content descriptions
- **Large touch targets**: Suitable for industrial/commercial use
- **Focus indicators**: Visible focus states for keyboard/remote navigation

---

## Technical Notes for V0

- Create a **Next.js/React** web app UI prototype
- Use **Tailwind CSS** for styling
- Use **shadcn/ui** components where applicable
- Implement **responsive grid layouts**
- Add **dark mode by default**
- Include **smooth transitions** for overlays and state changes
- Use **Lucide icons** or similar for UI icons

---

## Sample Data for Prototyping

### NDI Sources
```json
[
  {
    "name": "FoMaKo K20UH (CAMERA1)",
    "resolution": "1920×1080",
    "fps": 30
  },
  {
    "name": "OBS Studio (PC-MAIN)",
    "resolution": "1920×1080",
    "fps": 60
  },
  {
    "name": "NDI Test Pattern",
    "resolution": "1280×720",
    "fps": 30
  }
]
```

### Recordings
```json
[
  {
    "filename": "NDI_20260130_143052.mp4",
    "date": "2026/01/30 14:30",
    "duration": "00:15:23",
    "size": "1.2 GB",
    "thumbnail": "/placeholder-video-thumbnail.jpg"
  },
  {
    "filename": "NDI_20260130_120015.mp4",
    "date": "2026/01/30 12:00",
    "duration": "00:45:12",
    "size": "3.8 GB",
    "thumbnail": "/placeholder-video-thumbnail.jpg"
  },
  {
    "filename": "NDI_20260129_210532.mp4",
    "date": "2026/01/29 21:05",
    "duration": "00:08:47",
    "size": "0.2 GB",
    "thumbnail": "/placeholder-video-thumbnail.jpg"
  }
]
```

---

## Priority Features for V0

1. **Main screen** with NDI source list (most important)
2. **Player screen** with fullscreen video area and recording controls
3. **Recordings list** with grid/list view
4. **Settings screen** with toggle switches

Focus on **visual design, layout, and component structure** rather than functional NDI video streaming (which V0 cannot implement). The goal is to create a **high-fidelity UI prototype** that can be used as a reference for Android development.

---

## Success Criteria

✅ Clean, professional dark theme suitable for entertainment venues
✅ Large, easily tappable controls for 24-inch tablets
✅ Clear visual hierarchy and information architecture
✅ Smooth transitions between states (loading, recording, error)
✅ Responsive layout that adapts to different screen sizes
✅ Accessible design with high contrast and clear labels

---

**End of V0 Design Prompt**
