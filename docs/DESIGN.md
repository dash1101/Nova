# Nova design notes

The rules every screen follows, on the web and in the app. When a new feature doesn't fit one of these, change the
feature, not the rule (or argue for changing the rule here first).

## Don't overwhelm

- **Show what matters now; fold the rest away.** Settings most people never touch (which kinds of sound play, advanced
  timings, per-item switches) go in a dropdown (`expand()` on the web, an expandable row in the app) with a summary of
  what's inside, so a section reads as a few clear choices.
- **One decision per row.** A row says what it is and what it'll do; the details are a tap away.
- **Sensible defaults, no setup required.** Everything works the first time with nothing chosen; options refine it.
- **Plain words.** Say what happens ("Archives these 3 events"), not how it's built. No jargon a non-technical owner
  wouldn't use.

## Safe by default

- Nothing destructive or outward-facing happens from a single slip: it asks first, says exactly what will change, and
  (where it can) can be undone.
- A command never runs on the server by itself — pastes are held for Enter, power commands ask first.

## Feel

- Every look (Default, Material You, Glass, Mica, Breeze, Aero) is faithful in every element: cards, pills, dialogs,
  switches, sliders, toasts, windows. A new element gets every look's treatment, not just the default one.
- Motion is quick and springy, respects Reduce motion and Animation speed, and never blocks a tap.
- Sound and touch feedback: one sound and one buzz per press, from the look's own pack; each kind can be turned off.
- Performance comes first on modest hardware: effects degrade gracefully (Lighter effects, frame-rate limits) rather
  than stutter.
