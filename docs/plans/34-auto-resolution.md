# Plan #34 — Auto stream resolution matching the receiver display
Issue: #34 · Status: APPROVED (android-architect: approved with changes, integrated in "Review changes" below)

## Goal and acceptance criteria (copied from the issue)
- [ ] Sender settings offer `Auto` plus 720p / 1080p / 1440p; `Auto` is the default for new installs; a previously saved explicit value is preserved.
- [ ] With `Auto`, the negotiated size equals the largest 8-aligned size with the source aspect ratio that satisfies all of: ≤ receiver display, ≤ sender native, encoder and decoder size/area/rate limits at the negotiated fps. JVM tests cover at least: the three table rows of the issue, a 4K receiver with a 1080p-area-limited encoder, a 1080p-only decoder (Fire TV, `1920x1088`), portrait and landscape sources, and a source smaller than the receiver (no upscaling).
- [ ] Codec capability reporting uses area/rate limits on both sides; HELLO changes bump `PROTOCOL_VERSION` and update `docs/ARCHITECTURE.md`.
- [ ] Pixel 10 → Galaxy Tab S9 receiver with `Auto` and HEVC starts without an encoder configure error.
- [ ] Galaxy Tab S9 (portrait) → Pixel 10 receiver with `Auto` streams at ≥ 1080×1728 (was 672×1080).
- [ ] Galaxy Tab S9 → Fire TV Stick with `Auto`: same resolution as today's 1080p (1728×1080), no regression (≥ 58 fps, latency p95 within +5 ms, A/V ≤ 40 ms over 5 min).
- [ ] > 1080p sessions (Tab S9 ↔ Pixel 10) sustain ≥ 58 fps for 5 min on 5 GHz with adaptive bitrate; decoder drops ≤ 1 %.
- [ ] Rotation / one-app resize (`refit`) applies the same Auto rule.
- [ ] The stats overlay shows the stream resolution and the receiver display resolution.

## Approach
`CodecNegotiator.fitResolution` already takes the min of receiver display, codec per-axis maxima and
the "Max resolution" cap, keeps the source aspect ratio and never upscales (the source *is* the
sender's native size, so "≤ sender native" holds by construction). Two things are missing: an `Auto`
setting that removes the cap, and codec limits that are real. Per-axis maxima overstate what codecs
accept: the Pixel 10 HEVC encoder declares 3840×2176 but only ~1920×1088 of *area*; the Fire TV
decoder is 1920×1088. Level/area limits are not serialisable as a predicate, so each side measures
two numbers on device and announces them in its capabilities:
- `maxPixels`: largest frame area the codec accepts (`VideoCapabilities.isSizeSupported`),
  probed over a list of standard sizes in both orientations within the per-axis bounds;
- `maxPixelRate`: largest `w × h × fps` with `areSizeAndRateSupported`, probed over those sizes at
  60/30 fps.
The negotiator then picks the fps first (as today: min of refresh rates, codec max fps, preference),
and the largest 8-aligned size with the source aspect ratio such that `w ≤ maxW`, `h ≤ maxH`,
`w·h ≤ min(maxPixels)`, `w·h·fps ≤ min(maxPixelRate)` over encoder and decoder. A pure helper does
the area/rate scaling (`sqrt` of the ratio, then 8-alignment, then a final check loop shrinking by
8 px while any limit is exceeded).

`StreamPreferences.maxHeight = null` already means "no cap": it becomes `Auto` and the new default.
Settings store `AUTO` for it; a stored 720/1080/1440 is kept. Old installs that saved the old
default 1080 keep 1080 (indistinguishable from an explicit choice; acceptable and documented).

If the encoder still rejects the size at `configure`, the sender steps down instead of failing: it
re-fits with a cap one ladder step lower (native → 1440 → 1080 → 720 lines) and announces it with the
existing `VIDEO_RECONFIG` (ADR-010), at most 3 times. A decoder that rejects a size it announced
remains an error (BYE ERROR, as today): with area/rate limits in HELLO it should not happen, and a
decoder-driven step-down would need a new message (follow-up if seen in practice).

Rejected alternatives:
- *Send codec level/profile and compute limits from tables*: vendor codecs often declare a level
  but enforce lower block counts; measuring with the platform's own predicate is more faithful.
- *Probe the exact candidate size at negotiation time*: the decoder lives on the receiver; a round
  trip per candidate would slow the handshake.

## Changes per module
`:core:protocol`
- `VideoCodecCapability(+ maxPixels: Int, + maxPixelRate: Long)` with defaults `maxWidth*maxHeight`
  and `maxPixels*maxFps` for callers/tests that do not care.
- `ControlMessage` HELLO/CAPS video capability: append `u32 maxPixels`, `u32 maxPixelRate` (sat. at
  2^32-1). `ProtocolInfo.PROTOCOL_VERSION` 4 → 5; round-trip tests.
- `CodecNegotiator`: fps decided before size; `fitResolution(…, fps)` applies area and rate limits;
  `refit` same rule. New `internal fun fitWithin(srcW, srcH, maxW, maxH, maxPixels, maxPixelRate, fps)`.
  `fun stepDown(current: VideoConfig, sender, receiver, prefs): VideoConfig?` for the encoder fallback.
`:core:media`
- `AndroidCodecs.describe`: probe `maxPixels`/`maxPixelRate`; `maxFps` probed at the largest
  supported 16:9 size ≤ 1080p as today (unchanged meaning). `CodecDescriptor` carries both.
  Pure probe helper `CodecProbe.limits(isSizeSupported, isRateSupported, maxW, maxH)` for JVM tests.
`:feature:sender`
- `SenderSettings`: `HEIGHTS` gets `Auto` (stored `AUTO`/absent → null); default `maxHeight = null`.
  UI segmented control: Auto · 720p · 1080p · 1440p. `ProjectionService`/`SenderActivity` extras: use
  0 for Auto instead of defaulting to 1080.
- `ProjectionHost`: on encoder configure failure at start or in `reconfigure`, `stepDown` + VIDEO_RECONFIG
  (≤ 3 tries) before giving up.
`:feature:receiver`
- Overlay line 1: `HEVC 1728×1080 on 1920×1080 display` (display size from `DisplayInfo`).
- HELLO uses the new capability fields (from `CodecCatalog`).

## Protocol
v5: video capability +8 bytes (`maxPixels` u32, `maxPixelRate` u32). No other change. v4 peers get
the existing version-mismatch message. ADR-004 updated (negotiation rule) and the message table.

## Threading and resource lifecycle
Probing runs where the catalog is built today (once per process, off the main thread as now). Step-
down reuses the existing encoder rebuild path under `videoLock`. No new resources.

## Risks and mitigations
- *Higher resolutions raise latency/bitrate*: bitrate keeps the bpp rule with the 40 Mbps ceiling;
  AIMD unchanged; caps remain one tap away. Measured on device (5 min runs).
- *`isSizeSupported` false negatives on odd vendor codecs* → probe list includes 1920×1080 and
  1280×720 in both orientations; if nothing passes, fall back to per-axis area (today's behaviour).
- *API 28 receivers*: `VideoCapabilities` methods used exist since API 21.
- *Fire TV regression*: its decoder area (1920×1088) and display (1920×1080) give the same 1728×1080
  as today; covered by a JVM test and a device run.

## Test strategy
- JVM (`:core:protocol`): table tests for the issue's rows, 4K receiver + Pixel HEVC area limit,
  Fire TV decoder, portrait/landscape, small source, rate limit lowering size at 60 fps, `refit`,
  `stepDown` ladder, v5 HELLO round trip.
- JVM (`:core:media`): `CodecProbe` with fake predicates (area-limited, rate-limited, unlimited).
- JVM (`:feature:sender`): settings decode/encode of Auto and explicit values, default for new installs.
- Device: Tab S9 → Fire TV (Auto = 1728×1080), Tab S9 portrait → Pixel 10 (≥ 1080×1728), Pixel 10 →
  Tab S9 (no configure error), overlay shows both sizes; 5-min runs with fps/latency/drops.

## Implementation steps
- [x] `feat(protocol): area and rate limits in video capabilities (v5)` — model, wire, version, tests.
- [x] `feat(protocol): Auto resolution fit with area/rate limits and step-down` — negotiator + tests.
- [ ] `feat(media): probe codec area and pixel-rate limits` — AndroidCodecs, CodecProbe, tests.
- [ ] `feat(sender): Auto max resolution, default for new installs` — settings, UI, extras, tests.
- [ ] `feat(sender): step down the size when the encoder rejects it` — ProjectionHost.
- [ ] `feat(receiver): show the display resolution in the stats overlay`.
- [ ] `docs: ADR-004 Auto resolution and protocol v5`.

## Review changes (android-architect, integrated; they override the sections above where they differ)
- **Encoder limits are checked exactly and locally, before START.** `CodecNegotiator.negotiate`/`refit`
  take an `encoderAccepts(codec, w, h, fps)` predicate (default: always true); on device it is
  `areSizeAndRateSupported` of the encoder `catalog.best` will use. The fit shrinks until both the
  decoder limits from HELLO and this predicate accept, so START / VIDEO_RECONFIG already carry a size
  the encoder takes. A `configure` failure that still happens keeps today's path (ADR-010 retries);
  there is no post-START step-down message.
- **Area means 16×16 blocks.** Limits compare `ceil16(w)·ceil16(h)` (padded pixels), not `w·h`
  (1080×1928 is under 1920×1088 in pixels but over in blocks).
- **Probing = binary search**, per aspect ratio (16:9, 16:10, 4:3, 20:9, both orientations), of the
  largest 16-aligned height accepted by `isSizeSupported` within the axis bounds; `maxPixels` = the
  largest padded area found; `maxPixelRate` = that area × `getSupportedFrameRatesFor(w,h).upper`
  (catching `IllegalArgumentException`). If nothing passes: per-axis area (today).
- **Wire:** `maxPixels` i32 and `maxPixelRate` i64 (no saturation needed).
- **Reusable for #35:** public `VideoLimits(maxWidth, maxHeight, maxPaddedPixels, maxPixelRate)` with
  `intersect()`, and public `fitWithin(aspectW, aspectH, limits, fps, capHeight, accepts)` returning
  `Size?`; #35 fits its virtual display with the receiver display as the "source".
- `CodecCatalog.best` ranks by `maxPixels`, so the encoder used is the one whose limits were announced.
- Every `?: 1080` default (`ProjectionService` extras, `SenderActivity` saved state, `SenderScreen`)
  becomes Auto (extras: 0 = Auto); `encodePrefs` writes `AUTO`. Tests relying on the old default
  `maxHeight = 1080` are updated.
- Migration: a saved 720/1080/1440 is kept (acceptance criterion); device runs switch to Auto by
  hand. Recorded in ADR-012.
