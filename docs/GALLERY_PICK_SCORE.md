# Gallery Pick — how the score on each photo is calculated

Every matched photo in the grid shows a score from 0 to 1 in its corner. Higher
is better. Selfies and full-length photos are scored differently: a selfie by
the v2 selfie score below, a front or side photo by the body score that
follows it. The grid is then ordered by a number that adds how recent the
photo is, and the top photo of each type (selfie, front, side) is pre-selected.

## Selfies: the v2 score

A selfie that passed the gates (head tilted under 12°, turned under 20° and rolled under 30°,
mouth closed, eyes on the camera — under 12° off, under 5° up, under 9° down, and under 5° down unless MediaPipe also reads the eyes looking down —
not too dark (ISO under 2500), camera not far below or above, not blurred against the shopper's other close selfies (camera within 60 cm), nothing over the face, camera data present, not closer than 25 cm, not a
document) is scored on
six groups. Each group is the lowest of its readings, and each reading is a
straight line from 1 at its tolerance to 0 at its limit.

| Group | Weight | Readings, from full marks to zero |
|---|---|---|
| Pose | 30% | Pitch 3° to 15°, up or down, from the MediaPipe face model; yaw 5° to 20°; roll 2° to 30°, from the MediaPipe face model; camera 40 cm to 25 cm away (from the iris size and the lens); a camera below the face (pitch above 0°, the camera looking up at the face) 0° to 6°, never under 0.6, so 0.83 at +1° and 0.6 from +2.4° |
| Light | 20% | Brighter half of the face over the darker half 1.3× to 2.0×; face against the whole frame 0.8 to 0.4 (backlight); blown-out face pixels 1% to 5%; a colour cast scores 0.6 or 0.7; low light, the camera's ISO 1600 to 5000 |
| Gaze | 15% | How far the eyes point from where they usually sit in the shopper's own selfies (looking at the screen), 4° to 12° (looking into the lens is about 2° off); a head turned with the eyes back on the lens counts as looking at it |
| Expression | 15% | A closed smile, eyes held wide or brows raised, or pursed lips each cap the group at 0.5; a frown at 0.6; an open smile at 0.4 |
| Visibility | 10% | Share of the face oval that is skin or hair, 95% to 85% (with glasses, 8% of the oval is allowed for the frames); clear glasses cap it at 0.85 |
| Detail | 10% | Sharpness; eye spacing in the original 120 px to 60 px |

- T = pose^0.30 × light^0.20 × gaze^0.15 × expression^0.15 × visibility^0.10
  × detail^0.10. A geometric mean, so one poor group pulls the whole score
  down and nothing can make up for it.
- **Tier A** is T at 0.70 or more. A selfie whose eyes are under 90 px apart
  in the original (taken from far away) is Tier B whatever its T.
- Only a Tier A selfie is pre-selected. Tier B selfies stay in the grid for
  the user to pick.
- The pitch is the head's angle to the camera. A phone held low with the
  head level reads as a camera below and costs the pose group. A head tipped
  down toward a low phone faces the lens and reads level, so it is not
  caught.

### Example: a clean selfie, shown as 0.83

| Group | Value | What held it down |
|---|---|---|
| Pose | 0.56 | camera 33 cm |
| Light | 1.00 | |
| Gaze | 1.00 | |
| Expression | 1.00 | |
| Visibility | 1.00 | |
| Detail | 0.90 | sharpness |

- T = 0.56^0.3 × 0.90^0.1 = 0.84 × 0.99 = **0.83**, Tier A.

### Example: pursed lips, shown as 0.69

- Pose 0.42 (camera 31 cm, pitched 6°), expression 0.50 (pursed lips), the
  rest near 1. T = 0.42^0.3 × 0.5^0.15 × 0.94^0.1 = **0.69**, Tier B.

## Front and side photos: the body score

A front, three-quarter or side photo is a reference for the body, so it is
scored on how well the body shows, not on the face. The face only has to
match the selfie. Sunglasses, a smile or closed eyes do not lower it.

### Step 1: three regions, each 0 to 1

The body outline comes from Apple's person segmentation, and the joints
(shoulders, hips, knees, ankles, wrists) from Apple's pose reading. The body
is split into the torso, the legs and the arms. Each region is the lowest
of three readings, plus up to 0.1 for visible skin:

| Reading | 1 when | 0 when | What it means |
|---|---|---|---|
| Fit, torso | width under 1.05 × the shoulders or hips | 1.35 × or more | A loose or oversized top hides the shape |
| Fit, thigh | under 0.45 × the torso length | 0.70 × or more | Wide trousers or a skirt hide the legs |
| Fit, shin | under 0.30 × the torso length | 0.50 × or more | |
| Fit, arm | under 0.25 × the torso length | 0.45 × or more | Wide sleeves hide the arms |
| Outline | contrast at the edge 0.14 or more | 0.04 or less | Clothes that blend into the background |
| Light | mean light 0.10 or more | 0.03 or less | Only a silhouette fails; black clothes pass |

A region the joints cannot place scores 0.

### Step 2: what lowers a region

- **Garments that hide the shape.** Apple's whole-photo labels cap a region:

| Label | At | Caps |
|---|---|---|
| Suit or tuxedo | 0.40 or more | torso and arms at 0.2 |
| Lab coat (how a sherwani reads) | 0.30 or more | torso and arms at 0.2 |
| Suit or tuxedo | 0.22 or more | torso and arms at 0.5 |
| Gown, sari, kimono or wedding dress | 0.30 or more | legs at 0.2 |
| Hoodie | 0.50 or more | torso at 0.5 |
| Jacket | 0.60 or more | torso and arms at 0.5 |

- **Objects over the body.** A handbag, backpack, suitcase, umbrella,
  bicycle or motorcycle found by the object model takes away its share of
  each region: a bag over 20 percent of the torso leaves the torso at 0.8
  of its value. A chair, bench or couch counts only when the person is
  sitting on it. A box around 90 percent or more of the body is scenery
  and is ignored.
- **Sitting** multiplies the legs by 0.8 and the torso by 0.9.

### Step 3: the score T

- Visibility = 0.4 × torso + 0.4 × legs + 0.2 × arms.
- Openness, 0 to 1: 1 standing straight; 0.7 leaning 12° to 25°; 0.6
  sitting; 0.5 leaning over 25°, arms crossed, both hands hidden or ankles
  crossed; 0.3 lying.
- Quality: the sharpness of the body, 0.3 to 1.
- **T = visibility^0.7 × openness^0.2 × quality^0.1.**
- A busy top multiplies T by 0.8.

### Tiers and gates

- **Tier A** at 0.70 or more. **Tier B** from 0.45 to 0.70. Under 0.45 the
  photo is shown but marked not usable, and never pre-selected.
- **Not usable whatever T:** the body under 400 px tall in the original, a
  blurred body, or objects over 25 percent or more of the body.
- A document (an ID or page with the face on it) is still ruled out.

### Example 1: a fitted outfit, standing, shown as 0.85

- Torso 0.9, legs 0.8, arms 0.7. Visibility = 0.36 + 0.32 + 0.14 = 0.82.
- Standing straight, openness 1. Quality 0.8.
- T = 0.82^0.7 × 1 × 0.8^0.1 = 0.87 × 0.98 = **0.85**, Tier A.

### Example 2: the same photo in a suit, shown as 0.55

- The suit label reads 0.45, so the torso and arms are capped at 0.2.
- Visibility = 0.08 + 0.32 + 0.04 = 0.44.
- T = 0.44^0.7 × 0.98 = **0.55**, Tier B. Usable, but every fitted outfit
  ranks above it.

### Reading the number on the tile

- 0.70 or above: Tier A, the body shows well.
- 0.45 to 0.70: Tier B, usable. Something hides part of the body.
- Under 0.45: shown, but not used for the Muse unless the user picks it.

## The order of the grid

The grid is not sorted by the score alone. Each photo gets an order number:

- **Tier A first.** A Tier A photo, selfie, front or side, sends 1 + R; the
  rest send their score, so they follow in their own order.
- **Selfies:** R = 0.45 × T + 0.40 × P + 0.15 × recency. Recency is 1 for a
  photo from the last 12 months, falling to 0 at 36 months. P = 0.7 × 0.5
  (the aesthetic score is fixed at 0.5 until it exists) + 0.3 × the user's
  own choices: 0.5 if marked a favourite, 0.25 if edited, 0.25 if kept from a
  burst, capped at 1. A favourite is worth about 0.06, the same as being a
  year newer.
- **Fronts and sides:** R = 0.8 × T + 0.2 × recency, with recency 1 for the
  last 6 months and 0 at 24 months.

The number on the tile is still the score. Two photos may therefore sit in a
different order from their badges when one is newer or a favourite.

When the scan ends the grid is set once: the pre-selected photos first, then
the other selfies, then the other fronts and sides, each group in this
order.

## What is pre-selected

- The selfie: the Tier A selfie with the highest order. A selfie with the
  eyes held wide is chosen only when no other Tier A selfie is left.
- The two fronts and two sides: the highest order of each, one per repeated
  shot, usable only (0.45 and over). The second is taken from a different
  session, at least an hour from the first, or from the same session when
  the outfit is clearly different: the mean colour of the top and the
  trousers differs by 20 or more on the CIELAB scale (the same outfit
  measures under 10). With no such photo the slot stays empty, and the user
  can take one with the camera.
- The pre-selected fronts and sides stay selected. Tapping one shows a short
  message that it is the clearest photo of that kind. The selfie can be
  swapped.
