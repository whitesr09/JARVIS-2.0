# JARVIS-2.0 — UI Design Upgrade Plan

_Product: JARVIS-2.0, native Android (Kotlin + Jetpack Compose, Material 3). Audience: one phone-only beginner developer (iQOO Z3) who must be able to choose a direction in one read and build it in three levels without further design decisions. Every value in this document is final and implementable._

_Product facts that do not change (locked): 4 themes — **Sunset (default)**, Light, Dark, Red — plus user custom-hex background. Brand accent lineage is the coral family anchored on `#CE470F`. Developer section, credits, and all L1–L6 behaviour stay._

---

## 0. How this plan was made (skill attribution)

| Skill | What it contributed |
|---|---|
| `frontend-design` (Anthropic) | The working method: distinctive point of view over templated defaults; "spend boldness in one place" (here: the orb + hero surfaces); warnings against the generic SaaS-card kit and warm-cream-default look — both directly diagnosed in the audit below; copy rules (active voice, sentence case, errors explain the fix). |
| `design` (Design Studio) | Team process run for this plan: Design System Lead (tokens), UI Designer (directions/components), Motion Designer (motion system), UX Researcher + Accessibility Auditor (audit + WCAG pass), Content Designer (state/empty copy rules). Roadmap is staged D1–D3 like their Research→System→Polish flow. |
| `ui-ux-pro-max` | Rule priorities applied throughout: contrast ≥ 4.5:1, touch targets ≥ 44–48dp, micro-interaction duration 150–300ms, transform/opacity-only animation, line-height 1.5–1.75, no emoji as UI icons, glass surfaces in light themes need ≥ 80% opacity, muted text never lighter than slate-600 equivalent. (Its `scripts/search.py` data files are not present in this install — pairings chosen from its documented Google-Font pairing approach and verified as real, free, Android-bundlable families.) |
| `local_design_workflow` + Better Design catalog (`npx better-design ask`, consulted live) | Brand-system borrowing map (Linear, Stripe, Apple, Vercel kits) and the **OKLCH lightness-step method** for deriving token ladders: dark themes step backgrounds L 0.10 → 0.20 → 0.30 with text 0.76 → 0.96; light themes step 0.92 → 0.96 → 1.00 with text 0.40 → 0.15. Hex values below were derived on that ladder so elevation reads as *lightness steps*, not random greys. |
| `code-reviewer` (standing rule) | Reserved for D3: full review pass of the restyled UI code before ship. |

---

## 1. Audit — what is actually wrong today

Evidence gathered from `ui/Theme.kt` and all screens (line-level greps + reads, 2026-10-21). Ranked by user-visible impact:

1. **No typography system at all.** `Theme.kt` sets only colors; there is no `Typography(...)` anywhere in the app and no custom `FontFamily` outside the code canvas (`MarkdownText.kt` monospace). Every screen renders in the default Material 3 type scale and the device default font — the single biggest reason the app "looks like a template." Only 2 explicit `.sp` values exist in the entire UI; hierarchy is whatever each screen author happened to get from defaults.
2. **The SaaS-card kit.** Every card in every screen uses `CardDefaults.cardElevation(defaultElevation = 2.dp)` (9 call sites: Home ×2, Api, Avatars, Memory, Permissions, Settings, Updates) with ad-hoc radii — measured `RoundedCornerShape` values: 12, 14, 16, 18, 20, 24, 50 all in use with no system. Result: zero hierarchy — a weather card, a permission row, and the developer card all look identical in weight.
3. **Almost no motion.** The only animation APIs in the UI layer are `animateScrollToItem` (chat). Tab switches are hard cuts, there are no screen transitions, no press-state scaling, no list entry animation, no success/error feedback animation. The orb animates (Canvas) but nothing else in the app feels alive.
4. **The overlay HUD ignores the app theme completely.** `FloatingOverlayService.kt` hardcodes a light panel (`GradientDrawable` + text colors `0xFF1D2733` / `0xFF44536A` / coral `0xFFCE470F` at lines ~168–215). In Dark/Red themes the HUD is a bright white-ish card floating over dark apps; on Sunset it clashes with the peach background.
5. **Dark and Red themes are flat tints, not designed themes.** `Theme.kt` Dark/Red schemes change background/surface hues but keep identical structure, no depth layering, no accent adaptation beyond `primary`, and `secondary` jumps arbitrarily (sky blue `#7DD3FC` in Dark vs peach in Red). Custom-hex mode makes it worse: it overwrites `background` **and** `surface` with the same colour, collapsing all elevation layering into one flat plane.
6. **Hierarchy and spacing are improvised per screen.** Padding values are a scatter (12/14/16/18/20/32dp across screens, no scale), Settings is a 671-line screen of 32 near-identical rows with no grouping rhythm, and Home's greeting + weather card + tiles compete at the same visual weight.
7. **State feedback is weak.** Tool activity in chat is plain bubbles; permission status has no consistent pill language; empty states exist (Memory) but with default styling and no illustration/orb moment; the "thinking" state in chat is a text bubble, not a designed state.
8. **WCAG risks.** `#CE470F` on Sunset background `#FFF3EA` ≈ 4.6:1 (passes AA for normal text — the known simulator fix) but `onSurfaceVariant #6B4A35` on `surfaceVariant #FFE4D3` is ≈ 4.0:1 (fails for small text); several screens use `bodySmall`/variant text for real content. Icon-only buttons (chat trash, voice controls) are mostly 40–48dp but not audited as a set; text is not tested at 1.3×/2.0× font scale anywhere.

**Top 5 (the ones the user will *see* fixed):** #1 typography, #2 card sameness, #3 no motion, #4 HUD theme-blindness, #5 flat dark/red/custom themes.

---

## 2. Three design directions

All three keep: 4 themes + custom hex, coral lineage, Material 3, the orb as hero. They differ in how far they push. _(frontend-design rule applied: pick one memorable place to be bold — the orb and hero surfaces — and keep everything else disciplined.)_

### Direction A — "Sunset Glass" ⭐
- **Character:** The current Sunset identity, grown up — warm glass layers over a peach-to-amber ambience, premium and soft, unmistakably *his* app.
- **Palette sketch (Sunset):** bg gradient `#FFF3EA → #FFE3D1`, glass surface `rgba(255,249,244,0.78)`, primary `#CE470F`, primary-deep `#A83A08`, accent-amber `#F59E0B`, accent-rose `#E85D3D`.
- **Typography:** **Sora** (display/headlines — geometric, techy-warm) + **Inter** (body/labels) + **JetBrains Mono** (code, data, tool rows).
- **Surface/material:** Glassmorphism done properly for Compose: translucent surfaces (72–85% alpha) over a soft radial ambience drawn behind content, 1dp gradient stroke (white 55% → white 10%) on hero cards, tonal layering instead of shadows (shadows only under FAB and hero card).
- **Orb:** Particle orb gains a soft glass halo ring and a warm amber core; state colours shift inside the coral family (see §5).
- **Borrows:** Apple (materials + restraint, SF-style type ladder) and Stripe (disciplined gradient use — gradients only on hero/CTA/orb, never as decoration on every card).

### Direction B — "Midnight Console"
- **Character:** Dark-first precision instrument — near-black blue-tinted surfaces, hairline borders, coral demoted to a laser accent. The most "pro tool" of the three.
- **Palette sketch (Dark leads; Sunset becomes a light skin of the same system):** bg `#07090F`, surface `#0D1119`, raised `#141A26`, hairline `#FFFFFF14`, primary accent `#FF6B3D`, data-green `#34D399`, data-violet `#A78BFA`.
- **Typography:** **Space Grotesk** (display — engineered, distinctive) + **Inter** (body) + **JetBrains Mono** used *visibly* for status/labels/data (console feel).
- **Surface/material:** Flat + hairlines (no glass, no shadows except FAB); density slightly higher; 12dp radii; everything aligned to a strict grid.
- **Orb:** Thinner particle ring, technical crosshair ticks, mono status readouts around it.
- **Borrows:** Linear (border-over-shadow elevation, speed, density) and Vercel (mono+geometric pairing, black/white discipline). Pushes coral furthest from today's look — Sunset fans may feel it loses the warmth that made the app his.

### Direction C — "Warm Minimal"
- **Character:** Calm, airy, friendly — soft neutrals, one typeface, lots of air; the gentlest upgrade, closest to stock Material You.
- **Palette sketch:** bg `#FFFBF7`, surface `#FFFFFF`, variant `#F6EDE6`, primary softened `#D2542B` (deepened `#B23A0A` on press/for AA text), ink `#2B211C`, muted `#6F5D52`.
- **Typography:** **Manrope** only (display through label — one family, many weights) + **JetBrains Mono** for code.
- **Surface/material:** Flat white cards, 20dp radii, very soft shadows (`alpha 0.06`), no glass, no gradients except the orb.
- **Orb:** Unchanged mechanics, softer palette, slower breathing.
- **Borrows:** Apple HIG clarity + Arc's softness. Lowest risk, but also lowest distinctiveness — risks still reading as "nice template" (the frontend-design trap).

### Recommendation: **Direction A — Sunset Glass**
Why, in phone-developer terms: (1) It is the only direction that makes the *default* theme — the one every screenshot and first launch shows — look premium, instead of asking him to switch to Dark. (2) Glass + tonal layering is achievable in Compose today with alpha surfaces and gradient strokes (no exotic APIs, no blur dependency risk). (3) It keeps his coral brand and the Sunset identity he already chose, so the upgrade reads as "my app, levelled up," not a rebrand. (4) Its token ladder (OKLCH steps) fixes Dark/Red/custom-hex flatness as a by-product. Direction B is the fallback if he later wants a pro-tool feel; C if performance on the iQOO ever becomes a problem.

_Everything from §3 onward specifies Direction A._

---

## 3. Token system — "Sunset Glass" (Direction A)

Implementation home: `ui/Theme.kt` (rewrite) + new `ui/tokens/` (`ColorTokens.kt`, `TypeTokens.kt`, `Dimens.kt`, `Shapes.kt`, `MotionTokens.kt`). All screens consume tokens via `MaterialTheme` + a `JarvisTokens` composition local for glass/stroke/orb extras Material doesn't model.

### 3.1 Colour tokens per theme

Material roles are filled as below; `surface` in glass themes is stored **with alpha** in the `JarvisTokens` layer (`glassSurface`), while the Material `surface` stays opaque for dialogs/sheets that must not blend.

**SUNSET (default, light glass)**
| Token | Hex | Notes |
|---|---|---|
| background (base) | `#FFF3EA` | ambience gradient drawn over it: radial `#FFE0C7` top → `#FFF3EA` 55% → `#FFDFC9` bottom |
| surface (opaque) | `#FFF9F4` | dialogs, sheets |
| glassSurface | `#FFF9F4` @ 78% | cards over ambience |
| glassSurfaceRaised | `#FFFFFF` @ 88% | hero card, nav bar |
| surfaceVariant | `#FFE7D6` | chips, user-bubble alt |
| onBackground / onSurface | `#2B1608` | ≈ 13.9:1 on bg |
| onSurfaceVariant | `#5F4128` | deepened from today's `#6B4A35` → ≈ 5.2:1 on variant (fixes audit #8) |
| primary | `#CE470F` | 4.63:1 on `#FFF3EA`, 6.0:1 on white — CTA + large text + key labels |
| primaryDeep (text-on-light use / pressed) | `#A83A08` | ≈ 6.4:1 on bg — use whenever primary-coloured *small text* sits on bg/variant |
| onPrimary | `#FFFFFF` | 4.63:1 on primary — AA for large/bold; button labels are 15sp semibold (counts as large-scale) |
| primaryContainer | `#FFD9C7` | selected chip, nav indicator |
| onPrimaryContainer | `#5C1E00` | |
| accentAmber | `#B45309` | (raw `#F59E0B` fails on light — amber is decoration/orb only; `#B45309` ≈ 4.9:1 for text) |
| success | `#1E7B34` (≈5.4:1) / container `#DDF2E2` | |
| warning | `#9A6206` / container `#FBEED3` | |
| error | `#B3261E` / container `#F9DEDC` | Material error family |
| outline (stroke) | `#2B1608` @ 12% | card borders |
| glassStroke | gradient `#FFFFFF` 55% → 8% | hero/glass cards only |
| scrim | `#2B1608` @ 40% | dialogs |

**LIGHT (clean glass)** — background `#FFFFFF`, ambience radial `#F2F4F8` → `#FFFFFF`; surface `#F8F8FA`, glass `#F8F8FA` @ 82%, variant `#ECECF1`, ink `#1B1B1F`, onVariant `#4A4A55` (≈6.1:1, deepened from `#5A5A63`), primary `#CE470F`, primaryDeep `#A83A08`, container `#FFD9C7`, success `#1E7B34`, warning `#9A6206`, error `#B3261E`, outline `#1B1B1F` @ 10%.

**DARK (deep glass)** — background `#0A0E1A`, ambience radial `#16213D` top → `#0A0E1A` 60% → `#070912`; surface `#141A2E`, glass `#141A2E` @ 80%, raised glass `#1B2340` @ 88%, variant `#1E2742`, ink `#E8ECF5`, onVariant `#B7C1D9` (≈7:1, lifted from `#A9B4CC`), primary `#FF8A5C` (on bg ≈ 7.2:1), onPrimary `#331100`, primaryDeep-for-dark = primary itself, container `#5C2A12`, onContainer `#FFD9C7`, accent `#7DD3FC` kept as the *cool counterpoint* (data/vision states only), success `#4ADE80`, warning `#FBBF24`, error `#F2B8B5` / container `#601410`-family `#5C1710`, outline `#E8ECF5` @ 12%, scrim `#000000` @ 55%.

**RED (ember glass)** — background `#1A0505`, ambience radial `#3D0B0B` → `#1A0505` 60% → `#120303`; surface `#2A0B0B`, glass @ 80%, raised `#381010` @ 88%, variant `#3D1212`, ink `#F7E8E4`, onVariant `#E3BDB4` (≈7:1), primary `#FF6B5E`, onPrimary `#330D0A`, container `#5C1710`, onContainer `#FFD9D3`, accentAmber `#FFB59E`, success `#7ED492`, warning `#FFC46B`, error `#FFB4AB`, outline `#F7E8E4` @ 12%.

**Custom hex rule (fixes audit #5):** the user's hex sets *only the ambience base*. The system derives the ladder from it: surface = base lightened/darkened one OKLCH step (+0.04 L for light bases, −0.04 for dark), variant = two steps, ink auto-picked (`#1B1B1F` if base L > 0.65 else `#F5F2EE`), glass alpha unchanged. Implement as HSL lightness offsets in `Theme.kt` (`deriveLadder(base: Color)`) — the four presets use the hand-tuned tables above; custom uses derivation.

**Orb state hues (all themes, drawn on Canvas):** core coral `#FF7A3D`, mid `#CE470F`, rim amber `#F5A623`; listening adds rose `#FF4D6D`; speaking uses white-hot core `#FFD9C7`; thinking adds violet `#8B7CF6` at 35% mix; error desaturates to `#B3261E` at 60% saturation. Dark/Red themes reuse the same hues at +10% brightness.

### 3.2 Typography

Bundling: add TTFs to `app/src/main/res/font/` — `sora_semibold.ttf`, `sora_bold.ttf`, `inter_regular.ttf`, `inter_medium.ttf`, `inter_semibold.ttf`, `jetbrains_mono_regular.ttf`, `jetbrains_mono_medium.ttf` (Google Fonts, OFL, free; ≈ 700 KB total). Build `FontFamily` objects in `TypeTokens.kt`; set `MaterialTheme(typography = JarvisTypography)`.

| Style | Font | Size / Line height | Weight | Use |
|---|---|---|---|---|
| displayLarge | Sora | 36 / 44 | Bold | Home greeting |
| displayMedium | Sora | 30 / 38 | SemiBold | Voice screen status |
| headlineMedium | Sora | 24 / 32 | SemiBold | Screen titles |
| titleLarge | Sora | 20 / 28 | SemiBold | Card/section titles |
| titleMedium | Inter | 16 / 24 | SemiBold | Row titles, bubble names |
| bodyLarge | Inter | 16 / 25 (1.56) | Regular | Chat AI text, paragraphs |
| bodyMedium | Inter | 14.5→**14** / 22 | Regular | Rows, secondary content |
| bodySmall | Inter | 12.5→**12** / 18 | Regular | Captions only (never essential content) |
| labelLarge | Inter | 14 / 20 | SemiBold | Buttons |
| labelMedium | Inter | 12 / 16 | SemiBold | Chips, pills, nav labels |
| labelSmall | Inter | 11 / 14 | Medium | Timestamps, via-captions |
| code | JetBrains Mono | 13 / 19 | Regular | Code canvas, tool rows, data |

Rules: sentence case everywhere (no ALL-CAPS labels — frontend-design tell); Sora never below 20sp; body text never in `onSurfaceVariant` below 14sp (contrast rule).

### 3.3 Spacing, radius, elevation, stroke

- **Spacing (4pt):** `s1=4, s2=8, s3=12, s4=16, s5=20, s6=24, s8=32, s10=40, s12=48`. Screen horizontal padding = 20 (was random 12–32). Section gap = 24. Card internal padding = 16 (hero 20). List row vertical padding = 12.
- **Radius:** `rXs=8` (chips-in-cards, code inline), `rSm=12` (fields, snackbar), `rMd=16` (standard cards, dialogs' inner), `rLg=20` (hero card, chat bubbles, HUD), `rXl=28` (dialogs, bottom sheets), `rFull=999`. This replaces the 7 ad-hoc values (audit #2).
- **Elevation:** tonal first (Linear lesson): cards = glass surface + 1dp outline, **0dp shadow**. Raised = lighter glass step. Shadows only: FAB `6dp, primary @ 25%`, hero card `8dp, #CE470F @ 8%`, nav bar `12dp upward, ink @ 6%`. Compose: `shadowElevation` with `spotColor/ambientColor` set from tokens.
- **Stroke:** cards 1dp `outline`; glass/hero 1dp `glassStroke` gradient brush; focused field 2dp `primary`; selected avatar 2dp `primary` + 4dp `primaryContainer` outer glow ring.
- **Glass spec (Compose-feasible, honest):** true backdrop blur of arbitrary content is not reliably available in Compose; Sunset Glass therefore = (a) app draws the ambience gradient as the root background, (b) cards are 78–88% alpha surfaces over it — the gradient shows through, giving the glass read, (c) gradient stroke + top sheen (linear white 10% → 0% overlay on hero only). Do **not** use `Modifier.blur` on live content (perf on iQOO + it blurs the card's own content edge-to-edge).

---

## 4. Component specs

All sizes dp, colours = §3 tokens, type = §3.2. Press feedback everywhere: scale 0.97 + alpha sheen, 120ms (see §6).

- **Primary button:** height 52, radius `rMd`, background = primary gradient (`#E0521F → #CE470F` vertical), label `labelLarge` white, icon 20 left when present, disabled = variant bg + onVariant text, loading = spinner replaces label, button disabled during async (ui-ux-pro-max rule).
- **Ghost/secondary button:** height 52 (compact 44), transparent bg, 1dp outline stroke, primaryDeep text (Sunset/Light) / primary text (Dark/Red).
- **Danger button:** error container bg, error-deep text `#7A1A14` (light) / `#FFB4AB` (dark); used in ConfirmGate + destructive dialogs only.
- **Cards:** standard = glassSurface + outline + `rMd`, padding 16, elevation 0. **Hero card** (Home weather/status, provider-active card): glassSurfaceRaised + glassStroke + sheen + `rLg`, padding 20, soft shadow. **Flat row-card** (settings/permissions lists): no card — rows separated by 1dp outline dividers inside one glass container per group (kills the SaaS-card kit, audit #2).
- **Chips:** height 34, radius `rFull`, unselected = surfaceVariant bg + onVariant text; selected = primaryContainer bg + onPrimaryContainer text + 1dp primary stroke; filter chips get a 16dp leading check when selected.
- **Bottom nav + FAB:** bar = glassSurfaceRaised, top 1dp outline, height 68 + inset; item = icon 24 + labelMedium; selected = primaryContainer pill (56×32) behind icon + primary icon/label; unselected = onVariant. **Mic FAB:** 64dp circle, primary gradient, white mic 28, glow shadow (above), long-press hint tooltip "Talk to Jarvis"; while a voice session is live, FAB shows the live-state icon (waveform) in error colour per existing behaviour — keep the state, restyle the colours to tokens.
- **Chat bubbles:** user = primary gradient bg, white text `bodyLarge`, radius `rLg` with the bottom-end corner tightened to 6dp, max width 82%, padding 12×10. AI = glassSurface + outline, ink text, radius `rLg` with bottom-start corner 6dp, padding 14×12, full available width for code. **Provider caption** under AI bubbles: `labelSmall` onVariant, format `via Groq • llama-3.3-70b` (keep the L2 behaviour, restyled). **Tool rows:** full-width mono strip — `code` type, surfaceVariant bg, radius `rSm`, leading 18dp status glyph (🔧/✅/❌ become vector icons: Build/Check/Error — emoji-as-icon is banned by ui-ux-pro-max; the L4 strings carry emoji, so ToolRow renders the icon from the prefix and strips it from text). **System/notice bubbles:** centred, labelMedium, onVariant, no bubble bg. **Thinking state:** AI bubble skeleton — three pulsing dots (motion §6) + "Jarvis is thinking…" in onVariant, never plain text alone.
- **Code canvas (MarkdownText):** keep the fixed dark `#171019` panel in *all* themes (deliberate — code reads as an object), radius `rLg`, header 38dp with language label in JetBrains Mono 11sp `#C9B8AE`, Copy = ghost button (icon + "Copy", 32dp tall, `#FFD9C7` text), code `code` type `#F5EDE8`, horizontal scroll, 1dp stroke `#FFFFFF1A`. Inline code: surfaceVariant bg, `rXs`, mono.
- **Dialogs:** radius `rXl`, surface (opaque) bg, title titleLarge, body bodyMedium, actions = text buttons right-aligned, scrim token. **ConfirmGate** specifically: leading warning icon in error-container circle 44dp, action description in bodyLarge, Allow = danger or primary button depending on gate type (payment/delete = danger styling), Deny = ghost; 30s timeout state shows a thin linear progress under the title (visualises the existing auto-deny).
- **Settings rows:** min height 64, leading icon in 40dp squircle (`rSm`, primaryContainer bg, primary icon 20), title titleMedium, subtitle bodySmall onVariant, trailing = chevron / switch / value text (labelLarge, primaryDeep). Switch: Material 3 Switch with token colours (thumb white, track primary when on). Group headers: titleSmall Sora 15sp + 8dp top gap; groups live in one glass container each (see Cards).
- **Text fields:** filled style — glassSurface bg, radius `rSm` (top corners only is Material default; use full `rSm` via `TextFieldDefaults` shape override), 1dp outline, focus = 2dp primary + label primaryDeep, label labelMedium, supporting text bodySmall, password fields keep show/hide + paste actions (L2), error state = error stroke + error text (never colour alone — include icon).
- **Snackbar/toast:** inverse surface (`#2B1608` on light themes / `#E8ECF5` on dark), radius `rSm`, bodyMedium inverse text, action in amber-light `#FFB59E`, 12dp margin above nav.
- **Permission rows:** row layout as settings rows + **status pill** at trailing: Granted = success container + success text + Check icon; Missing = warning container + warning text; Blocked/denied-permanently = error container. Pill height 26, radius rFull, labelMedium 11sp. Grant button = compact ghost 36dp when Missing.
- **Avatar grid (AvatarsScreen):** 3 columns, cell = glass card `rLg`, avatar drawable at 64dp centred on transparent cell (avatars stay background-less per the L5 user rule), name labelMedium below; selected = 2dp primary ring + check badge 20dp top-end + slight scale 1.03; section header + "Floating avatar" start/stop card above the grid (hero style).
- **HUD panel (overlay, FloatingOverlayService):** the View-based HUD must consume the same tokens via a `HudTheme` mapping table in code (bg, ink, muted, accent, success, error per app theme — values copied from §3.1; it reads the app's current theme pref at service start). Panel = theme glass colour at 88% (dark themes: `#141A2E`E0; light: `#FFF9F4`E0), corner radius 20dp, stroke 1dp theme outline, padding 14, goal text 15sp semibold ink, step rows 13sp muted with status icon dots, progress bar 4dp track variant / fill primary, width 264dp, margin 12 from screen edge. No more hardcoded light panel (audit #4).

---

## 5. Orb treatment + state mapping

Orb stays the hero (frontend-design: boldness spent here). Changes to `OrbView.kt` are visual/motion only — the state machine from L3 is untouched.

- **Structure:** particle field (existing ~140 points) + new outer **halo ring** (1.5dp stroke, radial gradient coral→transparent, radius = orb × 1.18) + **core glow** (radial gradient, state colour, alpha breathes).
- **Amplitude response curve:** scale = `1 + 0.28 × easeOutCubic(amp)`; particle outward drift = `amp × 14dp`; glow alpha = `0.25 + 0.55 × amp`; clamp amp to 0..1 with attack 60ms / release 180ms smoothing so it never jitters.
- **States:**

| State | Palette | Motion |
|---|---|---|
| Idle | coral/amber (§3.1) | breathe: scale 1.0↔1.04, 2.8s sine; particles slow orbit |
| Listening | + rose mix | pulse ring every 1.2s; radius follows mic amp curve above |
| Speaking | white-hot core | waveform wobble amplitude × 1.4, faster particle shimmer |
| Thinking | + violet 35% | swirl: particles rotate 25°/s, core pulses 0.9s |
| Error | desat red | two quick shakes (±4dp, 240ms), then dim to 70% |
| Success flash (task done) | + success green 25% | single expanding ring fade, 600ms |

- VoiceScreen orb = 264dp; Home orb = 188dp (up from current, it is the hero); overlay bubble avatar is a static drawable (no orb) — unchanged.

---

## 6. Motion system

Tokens (`MotionTokens.kt`): `fast=120ms` (press, chip select), `med=220ms` (dialogs, HUD, card state), `slow=350ms` (screen transitions), `emphasis=600ms` (success ring, hero reveal). Easings: `Standard = FastOutSlowIn`, `Enter = Decelerate`, `Exit = Accelerate`, spring for FAB/HUD: `Spring(dampingRatio=0.8, stiffness=380)`. Animate **transform/opacity only** (ui-ux-pro-max).

- **Screen transitions:** tab switches = crossfade + 24dp horizontal slide toward the tab direction, `slow/Standard`. Sub-screens (Settings→API, →Memory, →Avatars) = slide-in from right 350ms + fade; back reverses.
- **List entry:** items fade + rise 8dp, stagger 30ms, capped at 8 items (rest appear instantly) — used on Settings groups, Memory list, Permissions. One orchestrated moment per screen, not per scroll (frontend-design restraint).
- **Buttons/cards:** press = scale 0.97 (`fast`), release springs back; cards don't lift on touch (tonal app), only FAB and primary buttons scale.
- **Orb micro-interactions:** tap = ripple ring + scale bounce (spring); state changes crossfade palettes over `med`.
- **HUD:** expand/collapse = height + alpha, `med` spring; step added = new row fades/slides 12dp; task finish = progress bar sweeps to full + success colour flash 600ms.
- **Feedback:** success = check draw-in (animated path) in snackbar/dialogs where a save completes; error = 240ms horizontal shake ×2 on the failing card/field (plus text — never motion alone).
- **Chat:** new message = fade+rise 12dp `med`; typing dots = 3 dots, 900ms loop, staggered 150ms.
- **Reduced motion:** read `Settings.Global.ANIMATOR_DURATION_SCALE` (and treat 0 as reduced): transitions become crossfade-only 120ms, orb breathing/swirl disabled (amplitude response kept at 50%), staggers removed. Expose via `LocalReducedMotion` composition local from `JarvisApp`.

---

## 7. Per-screen change list (priority order within each)

1. **HomeScreen** — P1: greeting in displayLarge Sora + date line in onVariant; orb 188dp with halo (§5); weather card → hero card spec; quick tiles → one glass group-card with 3 flat rows (Screen/Memories/Language) instead of 3 mini-cards; status pill uses pill language (§4). P2: greeting block gets the single orchestrated entry (orb scales in, content rises).
2. **ChatScreen** — P1: bubbles per §4 (gradient user bubble, glass AI bubble, mono tool rows with vector icons, thinking dots); composer = glass field `rFull` with send button as 44dp primary circle inside; P2: message entry motion; provider caption restyle; clear-chat moves into an overflow with danger confirm dialog.
3. **VoiceScreen** — P1: orb per §5 at 264dp, status in displayMedium, captions in bodyLarge centred max-width; LIVE/FALLBACK badge = pill spec; controls = 64dp mic toggle (primary when live) + 52dp end (danger ghost). P2: state palette crossfades; share-screen chip per chip spec.
4. **SettingsScreen** — P1: group into glass group-cards (Assistant, Voice, Wake, Digital Hands, Appearance, API, Memory, Developer, About) with group headers; rows per §4 (icon squircles); theme picker shows 4 live swatch cards with the actual bg/primary of each theme + custom hex field. P2: entry stagger; footer restyle (version in labelSmall, credits with Tech GPTx line preserved).
5. **ApiConnectionScreen** — P1: provider list = flat rows in one group-card with radio + key-set pill (success "Key set" / warning "No key"); editor card = hero style; Test Connection result = inline status banner (success/warning/error container) with latency in mono; fields per §4. P2: latency history sparkline — skip (scope discipline), note as future.
6. **PermissionsScreen** — P1: rows per §4 with status pills + Grant buttons; header summary card (hero) "n of 11 granted" with progress ring; GRANT ALL = primary button bottom. P2: granted rows collapse their button with `med` fade.
7. **MemoryScreen** — P1: search field per §4; category chips per spec; fact cards = standard cards with category pill + edit/delete icon buttons (48dp targets); count header in titleLarge. P2: empty state = small orb illustration moment + "Teach Jarvis something" primary button (content rule: emptiness invites action); import/export into overflow menu with icons.
8. **AvatarsScreen** — per §4 grid + hero control card; P2: selection spring scale.
9. **UpdatesScreen** — P1: roadmap becomes a vertical timeline (line + state dots: done = success, current = primary pulsing, upcoming = variant) in one group-card; version header hero card. P2: mark D-levels (design) appended after L6 in the same timeline.
10. **ConfirmGateDialog** — per §4 dialog + danger treatment + timeout progress bar.
11. **FloatingOverlayService HUD** — per §4 HUD spec + `HudTheme` token mapping for all 4 themes + custom (custom uses derived ladder ink/bg); bubble keeps transparent, background-less avatar (L5 rule); pulse/bounce animations switch to token durations (med=220).

---

## 8. Accessibility pass

- **Contrast method (baseline = the known fix):** every text/background pair in §3.1 was chosen on the OKLCH ladder and checked like `#CE470F` was (4.63:1 on Sunset bg, 6.0:1 on white). Targets: body text ≥ 4.5:1, large text (≥18sp, or 14sp semibold+) ≥ 3:1, non-text indicators ≥ 3:1. Known deliberate pairs: primary-on-bg is for CTA/large only; small primary-coloured text uses `primaryDeep #A83A08`. Glass rule (ui-ux-pro-max): body text never sits on glass below 85% effective opacity without the opaque-surface fallback — dialogs/sheets stay opaque for this reason.
- **Touch targets:** all interactive ≥ 48×48dp (icon buttons get 48dp hit area even when the glyph is 20–24dp); chips 34dp tall are *display+tap* only inside scrollable filter rows — bump tappable filter chips to 40dp height with 34dp visual via padding split… simpler: filter chips are 40dp tall, radius rFull. (Spec adjusted: §4 chip height applies to status pills (non-interactive); interactive chips = 40dp.)
- **Text scaling:** layout must survive 1.3× and 2.0× font scale: no fixed-height text containers (rows use minHeight), bubbles wrap, nav labels may ellipsize at 2.0× but icons remain; test pass at both scales in D3 QA.
- **Semantics:** every icon-only control keeps/gets a `contentDescription`; orb exposes a state description ("Jarvis is listening"); status is never conveyed by colour alone (pills and tool rows carry icons + text); focus order follows visual order on all screens.
- **Reduced motion:** §6 rule, verified in D3.

---

## 9. Implementation roadmap (his level style)

### D1 — Tokens + theme rebuild + core components
Files: rewrite `ui/Theme.kt`; new `ui/tokens/{ColorTokens,TypeTokens,Dimens,Shapes,MotionTokens}.kt`; new `res/font/*.ttf` (7 files) + `res/font/jarvis_fonts.xml` if needed; new `ui/components/{JarvisButtons,JarvisCards,Rows,Pills,Fields}.kt`; `ui/JarvisApp.kt` (nav + FAB + ambience background + LocalReducedMotion); `MainActivity.kt` (edge-to-edge + status/nav bar colour from tokens); `service/FloatingOverlayService.kt` (`HudTheme` map only — no layout change yet).
Risks: font TTF names/weights mismatch (verify each file's family name in D1 QA — a wrong name silently falls back); custom-hex derivation edge cases (pure black/white hex) — clamp L; Material `surface` with alpha breaks dialogs → keep Material surfaces opaque, glass only via `JarvisTokens`.
Exit criteria: app compiles (CI green), all screens render in new type + colours with zero screen-level edits except JarvisApp; theme switching incl. custom hex works.

### D2 — Screens restyle + transitions
Files: all 10 screens + `ui/MarkdownText.kt` (code canvas spec), `ui/OrbView.kt` (halo + palette crossfade hooks), `ui/ConfirmGateDialog.kt`, navigation transition wiring in `JarvisApp.kt` (AnimatedContent per tab/sub-screen), `ui/VoiceScreen.kt`, `ui/AvatarsScreen.kt`.
Risks: SettingsScreen is 671 lines — restyle by extracting group composables incrementally, CI after each screen group if needed; AnimatedContent + existing SubScreen state machine — keep state model, animate only the container; bubble width changes must not break the code-canvas min-width fix from the simulator/L2 era (`min-width:0` chain equivalent in Compose: code block keeps horizontal scroll).
Exit criteria: CI green; every screen matches §7 P1 items; transitions live; no behaviour regressions (L2–L6 flows untouched logically).

### D3 — Orb/motion polish + HUD restyle + QA
Files: `ui/OrbView.kt` (state palettes, amp curve, success ring), `service/FloatingOverlayService.kt` (HUD rebuild to §4 spec), motion polish across screens (staggers, press states), `ui/UpdatesScreen.kt` (timeline + D-levels).
QA (mandatory): code-reviewer skill pass over all touched UI files; contrast spot-check of the §8 pairs (compute ratios from hex); font-scale 1.3×/2.0× walk of Home/Chat/Settings; reduced-motion walk (animator scale 0); HUD checked in all 4 themes; CI green + on-device screenshot review by Nshd.
Risks: overlay Views can't use Compose tokens — HudTheme duplicates hexes (accept + comment the source of truth as §3.1); orb Canvas perf on iQOO (particle count unchanged, halo is 2 extra draws — fine).

**Out of scope for D1–D3:** new features, icon-set replacement (Material icons stay — one consistent library, per ui-ux-pro-max/design workflow), sound design, the simulator (web) — it keeps the old look; note in README that the simulator is a functional replica, not a design replica, after D2.

---

## 10. Decisions locked by this plan (no re-deciding during build)

1. Direction A "Sunset Glass"; fonts Sora + Inter + JetBrains Mono bundled as TTF.
2. Cards: tonal glass + outline, shadows only on FAB/hero/nav.
3. Radii/spacing/type scales exactly as §3.2–3.3.
4. HUD follows app theme via HudTheme map; avatars remain background-less.
5. Code canvas stays fixed-dark `#171019` in all themes.
6. Motion tokens + reduced-motion behaviour as §6.
