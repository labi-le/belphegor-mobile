# Belphegor Mobile — Design System

Apple-HIG-styled Android app (Material views underneath, iOS visual language on top).
Source of truth for every color, size, and pattern in `app/`. Derived from Apple HIG
(typography, color, dark-mode, lists-and-tables, toggles, buttons, tab-bars, motion);
units 1 pt = 1 dp. System font (no SF Pro — licensing); weights map Regular/Medium/Bold.

## 1. Principles

- Grouped-list utility app: gray canvas, white inset cards, hairline separators, **no elevation shadows**.
- One accent (systemBlue) on interactive elements only. Green/red are semantic (running/destructive), never decoration.
- Chrome recedes: flat bars in canvas color, centered inline title, bottom tab bar for the 4 sections. No FAB, no drawer.
- Settings apply immediately — no Save button. Explanations live in section footers, not placeholders.
- State is never color-alone: dot + word for status, switch + row label for toggles.

## 2. Color tokens (light / dark) — `res/values[-night]/colors.xml` + theme roles

| Token | Light | Dark (AMOLED base ramp) |
|---|---|---|
| `accent` (systemBlue) | `#007AFF` | `#0A84FF` |
| `ios_green` (running, switch-on) | `#34C759` | `#30D158` |
| `ios_orange` (starting/connecting) | `#FF9500` | `#FF9F0A` |
| `ios_red` (destructive, stop) | `#FF3B30` | `#FF453A` |
| `ios_gray` (off/unselected) | `#8E8E93` | `#8E8E93` |
| `switch_track_off` (fill @16/32%) | `#29787880` | `#52787880` |
| `label_secondary` | `#993C3C43` | `#99EBEBF5` |
| `label_tertiary` (placeholders, chevrons) | `#4D3C3C43` | `#4DEBEBF5` |
| `separator` | `#4A3C3C43` | `#99545458` |
| `selection_highlight` (`android:textColorHighlight`, accent @30%) | `#4D007AFF` | `#4D0A84FF` |
| `accent_container` / `on_accent_container` (M3 secondary-container roles) | `#D8E8FE` / `#0A5DC2` | `#0E3A6E` / `#9CC7FF` |
| canvas (`windowBackground`, `colorSurface`) | `#F2F2F7` | `#000000` |
| card (`colorSurfaceContainer`) | `#FFFFFF` | `#1C1C1E` |
| dialog (`colorSurfaceContainerHigh`) | `#FFFFFF` | `#242426` |
| field fill (`colorSurfaceContainerHighest`) | `#E5E5EA` | `#3A3A3C` |
| `colorOnSurface` | `#000000` | `#FFFFFF` |
| `colorOnSurfaceVariant` | = `label_secondary` | = `label_secondary` |
| `colorOutline` | = `separator` | = `separator` |
| `colorSecondary`, `colorControlActivated` (cursors, handles, progress) | = `accent` | = `accent` |

Dark = iOS *base* ramp on purpose (OLED black canvas, `#1C1C1E` cards, `#242426` elevated dialogs — one step over cards, kept below the washed `#2C2C2E` so the flat opaque panel doesn't read gray on true black).

## 3. Typography (iOS text styles → sp)

| Use | Size / weight |
|---|---|
| Toolbar title (inline nav title) | 17 medium, centered |
| Status title (Title 3) | 20 semibold(bold) |
| Hero number (Title 1) | 28 bold |
| Row title / body / field text | 17 regular |
| Footnote (subtitles, meta, footers, dim text) | 13 regular, `label_secondary` |
| Section header | 13 regular, ALL CAPS, `label_secondary`, inset 16 |
| Logs | monospace 12 |
| Tab labels | 11 |

## 4. Component primitives (`Views.kt`)

- `card {}` — inset group: radius 10, **zero elevation**, card color, **zero content padding**
  (rows own their 16 dp horizontal / ~11 dp vertical padding), 16 dp side margins on the section root, 24 dp gap to next section header.
- `divider()` — 1 px hairline in `separator`, leading inset 16 (layout-direction relative), flush trailing; between rows only, never after the last.
- `switchRow(title, subtitle)` — ≥44 dp row, title 17 / subtitle 13, trailing `MaterialSwitch` tinted iOS:
  white thumb, `ios_green` / `switch_track_off` track, no thumb icons. Row tap toggles; switch is the single a11y node.
- `fieldRow(label, placeholder)` — 44 dp row: label 17 leading, borderless right-aligned `EditText` 17 trailing (`textAlignment = viewEnd`), placeholder in `label_tertiary` (HIG placeholderText). No boxes, no floating labels. At large Dynamic Type (`fontScale ≥ 1.3`) label and value stack vertically so the value keeps its width.
- `sectionLabel` / `footerLabel` — Footnote 13 caps header / sentence-case footer, inset 16.
- `buttonRow(label)` — grouped-list button: full-width 44 dp row, `accent` 17 text (the iOS "Sign Out" pattern).
- `navRow(label, value)` — iOS disclosure row: label 17 leading, secondary value + `ic_chevron` trailing, whole row tappable (Appearance picker). A `buttonRow` tinted `ios_red` is the destructive variant (Clear saved peers).
- Status dot — 10 dp oval: `ios_green` running / `ios_orange` starting / `ios_gray` stopped, always paired with the status word.
- `capped()` / `scroll()` — section bodies cap at 640 dp and center, keeping a readable column on wide/landscape screens.
- Peer row — 9 dp green dot, name 17 semibold, meta 13 secondary, 44 dp min.

## 5. Navigation & screens

- **Bottom tab bar**: Dashboard `ic_nav_dashboard` / Nodes `ic_nav_nodes` / Settings `ic_nav_settings` / Logs `ic_nav_logs` — SF-style monoline glyphs (stroked vectors redrawn from Lucide/Feather).
  Canvas background + top hairline, no M3 active-indicator pill, selected `accent`, unselected `ios_gray`, labels always.
- **Toolbar**: canvas color, centered 17 medium title. Dashboard titles with the app name.
  Per-tab actions (borderless, accent): Nodes → `+` (add node, borderless `ic_add` glyph), Logs → `Clear` text action.
- **Dashboard**: status card (dot + Running/Stopped + trailing sync switch; self line; meta footnote) and
  a tappable stat card (CONNECTED NODES header, hero count, trailing `ic_chevron` disclosure glyph in tertiary) → Nodes tab.
- **Nodes**: three grouped sections — CONNECTED (live peers), FOUND ON YOUR NETWORK (LAN-discovered peers not yet saved; tap a row to save, hidden when empty) and SAVED PEERS (red `ic_remove_circle` to delete).
- **Settings**: sections APPEARANCE / IDENTITY / NETWORK / CLIPBOARD / BEHAVIOR / UPDATES / BACKGROUND / PEERS. Fields auto-save on change, switches on toggle. APPEARANCE opens a System/Light/Dark single-choice picker; NETWORK adds Discovery interval, Keep-alive, Wi-Fi only; CLIPBOARD carries Send/Receive/Sync-files toggles plus max file size (MiB) and max files per copy; BEHAVIOR adds Discover in background; PEERS is a destructive Clear saved peers. Network/file knobs apply on the node's next start; Appearance, Send/Receive and Wi-Fi-only apply live. Explanations and version live in footers.
- **Dialogs**: MaterialAlertDialog on themed surfaces with iOS chrome — 14 dp corners, centered 20 sp medium title;
  standalone dialog fields use the §4 grammar on a `field fill` rounded surface with a plain red footnote error. The add-node dialog is manual `ip:port` entry only (discovered peers live on the Nodes tab).
- **Notification**: `ic_stat_sync` white clipboard glyph, `accent` tint.

## 6. Motion & interaction

- Default control motion only (switch 0.2 s); no custom entrances. Row/button ripples inherited from Material, but the **bottom tab bar has no ripple** (`itemRippleColor` transparent) — tabs change color instantly like iOS, with no press animation.
- **Appearance override**: `AppCompatDelegate.setDefaultNightMode` from the Appearance pref, applied before `super.onCreate` — forces System/Light/Dark regardless of the OS setting.
- **Wi-Fi only** pauses the node on metered networks via a default-network callback: the node is torn down (the FGS stays alive), the notification reads "Paused — waiting for Wi-Fi", and the dashboard shows "Paused" (orange dot, switch left on); sync resumes automatically when Wi-Fi/Ethernet returns. Send/Receive gate the clipboard bridge live; Discover-in-background holds the multicast lock with the screen off (extra battery).
- Scroll-edge cue: a 1 px `separator` hairline under the toolbar appears only while the active section is scrolled.
- Immediate inline feedback ("Starting…" status text) instead of toasts for state the UI already shows.
- Hit targets ≥44 dp. Swipe-anywhere-right opens nothing (drawer removed); system back exits.
- Grouped-list insets are layout-direction relative (`marginStart` / `paddingRelative`), so the UI mirrors correctly in RTL locales.

## 7. Accepted debt

- System font, not SF Pro; SF-style monoline icons redrawn from Lucide/Feather (ISC/MIT), not SF Symbols (license). Material ripple/switch motion and the M3 shrinking unchecked thumb, not UIKit physics (a custom constant thumb breaks MaterialSwitch rendering).
- Toolbar title stays inline (no large-title collapse machinery).
- Toasts remain for out-of-view failures (update check/download errors, permission hints).
- Shared secret field is plain text by design — users must match it against the desktop `--secret` flag.
