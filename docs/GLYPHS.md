# Glyphs — the HUD design system

Every mark that reaches the glasses is a 24×24 stroked vector in one green.
This document is the recipe; `NexusGlyphArtTest` is what stops it drifting.
If you change the rules here, change that test in the same commit — a design
system that lives only in prose is one our own PhotoSync mark already escaped.

## Why monochrome outlines

The optics are additive and green: the display emits light and adds it to what
the wearer is looking at. Black emits nothing, so it reads as transparent —
which is why every HUD panel is pure black rather than a "nicer" translucent
grey. It also means a filled shape is a solid block of emitted light sitting in
the wearer's field of view, while an outline is a few lit pixels that describe
the same object. Outlines read better, cost less light, and stay legible
against a bright street.

A full-colour logo does not degrade gracefully here. It lands as a green blob.

## The invariants

These are enforced. A vector that breaks one fails the build.

| Rule | Value |
|---|---|
| Size | `android:width="24dp"`, `android:height="24dp"` |
| Viewport | `viewportWidth="24"`, `viewportHeight="24"` |
| Colour | `#FF4DFF8C`, and nothing else in the file |
| Primary stroke | at least one path at `strokeWidth="1.7"` |
| Gradients | none |

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="…"
        android:strokeColor="#FF4DFF8C" android:strokeWidth="1.7"
        android:strokeLineCap="round" android:strokeLineJoin="round" />
</vector>
```

## Size floors: what the glyph has to survive

Measured, not guessed: rendered at 24, 36 and 48 px, the maneuver family stops
being distinguishable at 24. `turn-sharp-left` collapses into the same few lit
pixels as `turn-left`, and `roundabout` — a circle, an entry and an exit inside
24 units — turns to mush. At 36 both read cleanly.

**A glyph with siblings needs 32dp.** That covers the maneuvers and the
transport controls: their job is not to be recognised but to be told apart from
something that looks almost the same. A chip that wants one gives it 32dp or it
does not show one.

This is a floor on the *renderer*, not a licence to simplify the glyphs.
Drawing `roundabout` with fewer strokes to survive 24dp would mean drawing
something that is no longer a roundabout.

**A glyph with no sibling can go smaller, if it was drawn for it.** `phone` in
the status badge renders at 13dp — the exact size of the ROM's own indicators,
because a badge that respected the 32dp floor would tower over the row it is
trying to disappear into.

`phone` also carries this section's second lesson: **a silhouette has to be
unmistakable in its surroundings, not just within our set — and it has to be
of this decade.** It took three draws. The first was a narrow rounded pill:
honest to the object, unique among our glyphs, and unreadable in place — next
to a percentage, one element from the ROM's own battery pill, it read as a
second battery. The second overcorrected into a handset: nothing says "phone"
harder, but it is a rotary-era object on AR glasses, and in a status row a
handset means *call in progress*. The shipped draw is the phone the wearer
actually owns — a wide flat slab (13 of 24 units; the pill's narrowness was
half its problem) with a home dash for a screen cue. When your glyph lands
next to marks you do not own, check what *they* make it look like, and what
the *row* makes it mean.

If you add a glyph, render it at 13, 24, 36 and 48 against the ones it will sit
next to — including the ROM's, if it lands in the status row — before believing
it works. A glyph that only reads at 96dp is a glyph that does not read, and a
glyph you have not seen at its smallest is a glyph you have not finished.

## What is left to your judgement

The invariants are a floor, not a template. Craft inside them is expected:

- **Secondary weights for small details.** `ic_plugin_bus` draws its body at
  1.7, its headlights at 1.9 and its wheels at 1.5. The headlights are
  zero-length round-capped strokes — that is how you draw a dot in this system.
- **Fill for small solid accents**, alongside a stroked primary shape.
  `ic_plugin_cart`, `ic_plugin_game` and `ic_plugin_feed` each do this. What is
  not allowed is a fill-*only* mark with no stroked shape at all.
- **Round caps and joins** unless you have a reason. Nothing in the set has one
  yet.
- **Keep to one path** where the shape allows it. Several is fine when it
  doesn't.

## The two kinds of mark

They look the same and mean different things. Do not mix them up.

**Plugin icon — identity.** Who is speaking. Chosen once, per plugin. Prefer a
built-in key (`docs/PLUGINS.md` lists them); if none fits, ship your own
geometry with `com.anezium.rokidbus.plugin.GLYPHS` and keep the matching phone
drawable in `com.anezium.rokidbus.plugin.ICON_DRAWABLE`. Files are named
`nexus_glyph_<plugin>.xml` in the plugin, and `ic_plugin_<key>.xml` for the
built-in set.

**HUD glyph — state.** What is happening right now. Changes many times per
session: one route emits `turn-left`, `straight`, `turn-right`, `arrive` within
minutes. These belong to the platform, not to a plugin, which is what keeps the
HUD coherent when several plugins are live at once. Files are named
`ic_glyph_<name>.xml`; the registry is `NexusGlyphs`.

## Custom plugin glyphs

A plugin declares custom geometry as a string-array of `name|pathData`
entries, then points its service metadata at that array:

```xml
<string-array name="nexus_glyphs">
    <item>my-mark|M4,4 L20,20</item>
</string-array>

<meta-data
    android:name="com.anezium.rokidbus.plugin.GLYPHS"
    android:resource="@array/nexus_glyphs" />
```

The plugin owns only the 24-unit path geometry. Nexus always supplies the
green colour, 1.7 stroke, round caps and joins, bounds, and placement. A plugin
may declare at most 8 glyphs, each path may contain at most 1024 characters,
and names use lowercase letters with single inner hyphens.

For a launcher identity, set `com.anezium.rokidbus.plugin.ICON` to the custom
glyph's name. Built-in icon keys still win; otherwise the glasses look up that
name inside the declaring plugin's namespace. Two plugins may therefore both
declare `my-mark` without colliding. An unknown state-glyph name renders as
`dot`; a missing launcher custom mark keeps the launcher's existing
legacy/grid fallback.

## The glyph set

Wire values are kebab-case. Android resource names cannot contain a hyphen, so
the file is `ic_glyph_turn_left.xml` for the wire value `turn-left` — that is
the only place the two spellings differ.

**Controls** — `play` `pause` `stop` `next` `prev`

**Navigation** — `straight` `turn-left` `turn-right` `turn-slight-left`
`turn-slight-right` `turn-sharp-left` `turn-sharp-right` `u-turn` `roundabout`
`arrive`

**State** — `package` `walk` `timer` `phone`

**Getting there** — `bus` `tram` `train` `metro`

The vehicle of the current leg of a route. They are drawn as fronts, the way a
stop sign shows them, and told apart by one feature each: the tram's pantograph,
the train's split windscreen and rails, the metro's ringed M. A line number is
not a glyph; it goes in the activity's `badge`.

**Answering** — `reply` `send` `retry` `cancel` `mic` `keyboard`

The first five arrived with the notification relay, and they are the set's first
marks about *responding* rather than about a thing or a maneuver. They are
shared rather than plugin-supplied on purpose: a reply is a reply in any plugin
that asks a question, and five plugins each drawing their own would give the
wearer five slightly different arrows for one idea. They are drawn to be told
apart at a glance in one row — a hooked arrow, a paper plane, a broken ring, a
bare X, a capsule — because that row is where all five appear at once.
`keyboard` joined them for answering by typing instead of speaking: a wide body,
one row of keys and a space bar, landscape where the `mic` capsule is upright.

**Fallback** — `dot`

## Adding one

Adding a glyph is safe; removing one is a wire break. `NexusGlyphs.drawableFor`
falls back to `dot` for anything it does not recognise, and hubs validate only
that a value is *well-formed* (lowercase, `a-z` and single inner hyphens, ≤ 24
chars) rather than that it is a member of the set. That is deliberate: a
membership check would turn every future glyph into a hard version gate, where
a plugin built against a newer SDK is rejected outright by an older hub instead
of degrading to a dot.

So: add the vector, add the mapping in `NexusGlyphs`, list it above. Do not
remove one that has shipped.
