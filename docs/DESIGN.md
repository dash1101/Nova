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

## Readable everywhere

- **Readability (Appearance → Icons and text)** has three settings. *Off* draws text exactly as the look does. *Always
  stronger* uses level 2 everywhere. *Automatic* (the default) strengthens text only where it's needed:
  - **Level 1:** see-through surfaces over a busy background. That means Glass at 30 %+ clarity, Aero at 55 %+, Mica at
    75 %+, or the Default look at 30 %+ transparency, over a picture, a living wallpaper or the vivid/sky backgrounds.
    Gray and accent text move toward the text color, and text gets a soft halo in the page color.
  - **Level 2:** the same, but where the picture's measured brightness fights the text (light text on a picture brighter
    than 62 %, dark text on one darker than 30 %, after its dimming), or the surfaces are nearly clear (Glass 70 %+,
    Default 60 %+). Gray and accent text move further toward the text color, and text gets a little bolder.
  - **Text straight on the background** (section names, notes, titles) over a picture or living wallpaper always gets
    the halo. On the web, notes also get a frosted backing.
- **Solid where there's no blur.** A dialog or menu that can't blur what's behind it (Breeze) is fully opaque. See-through
  without blur puts the page's text inside the dialog's.
- **Reachable by keyboard and screen readers.** Every row that acts like a button is focusable and answers Enter and
  Space. Switches say whether they're on. Icon-only buttons have a label. Focus shows as a clear ring.

## Appearance is a few short groups

Look, Colors, Background, Icons and text, Motion and effects, Sounds and touch, Home. Each choice shows what it's set to
now. Pickers with many options open a dialog or a gallery with previews. Sliders that tune one thing sit together in a
dropdown. A setting that does nothing here isn't shown: Vibration on devices that can't buzz, Color strength outside
Material You.
