# Measuring latency

RiriProjector reports two latencies. The glass-to-glass one is measured by hand.

| What | Where | Includes | Excludes |
|---|---|---|---|
| **capture→shown** (app) | Receiver overlay ("latency X ms (p95 Y, ±Z) capture→shown"), sender stats ("Latency: …") | encode, send queue, Wi-Fi, decode, lip-sync delay, queueing for display | the sender's composition before capture, the receiver's display/HDMI pipeline and panel |
| **glass-to-glass** (manual) | this procedure | everything the eye sees | — |

## How capture→shown is computed

- Video frames carry the sender's `CLOCK_MONOTONIC` time at composition.
- The receiver records when each frame reached its display (`OnFrameRenderedListener`).
- The 4-timestamp PING/PONG exchange estimates the clock offset between the devices (ADR-003). The
  receiver computes `shown − (pts + offset)` for every frame and reports the mean and p95 over the last
  ~120 frames (about 2 s at 60 fps; longer on a static screen, where frames are repeated less
  often). Clock-offset refinements keep the window; a new link restarts it.
- The error is at most ±RTT/2 (shown as ±Z).
- In lip-sync mode the number includes the video delay D that keeps video aligned with sound. Both
  the overlay and the sender show D separately.

## Glass-to-glass procedure

**You need:**
- a phone that records slow motion (240 fps is best; 120 fps is acceptable);
- a stopwatch page with millisecond digits, or any app that redraws a millisecond counter every
  frame.

**Steps:**
1. Open the stopwatch on the sender and start mirroring to the receiver. Use the mode you want to
   measure (Lip-sync or Low latency).
2. Place the sender next to the receiver so that both counters are in the camera frame and sharp.
3. Record about 10 s in slow motion.
4. Step through the video frame by frame. Pick **20 frames** where both counters are readable and
   not mid-transition. For each one, write down `sender − receiver` in milliseconds.
5. Report the **median** and the **p95** (the 19th of the 20 sorted values). Put the overlay's
   capture→shown number from the same moment next to them.

**Expected relation:** glass-to-glass ≈ capture→shown + the sender's composition (≈ 1 frame) + the
receiver's display pipeline. On TVs, game mode/ALLM usually cuts the display pipeline from 50–100 ms
to 10–20 ms. A large gap between the two numbers therefore usually points at the receiver's
settings, not at the app.

## Targets (docs/PLAN.md)

| Mode | Glass-to-glass p95 |
|---|---|
| Low latency | ≤ 100 ms (stretch: ≤ 60 ms) |
| Lip-sync | ≤ 100 ms + D, with A/V within ±40 ms |

## Recording results

Add a row per run to the PR or issue, with device pair, network, codec and resolution, mode, median,
p95, overlay mean/p95, and notes (for example "TV game mode on").
