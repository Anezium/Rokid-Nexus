# Additional navigation sources — 2026-09-27

Only `Medium_Phone_API_36.1` / `emulator-5554` was used. No physical phone or
glasses were touched. Routes use public central Paris and Moscow landmarks.
Raw notification dumps, screenshots, APKs and the temporary notification-listener
helper live under the system temporary directory, outside Git. Fixtures below
are text fields, not screenshots or personal route histories.

## Evidence and limits

| App/build | Observed on the emulator | Derived from code / still unverified |
| --- | --- | --- |
| OsmAnd+ 5.4.4, F-Droid x86 | English/French driving: `80 m • Head`, `300 m • Turn right and go`, `15 m • Turn right and go`, `300 m • Tournez à droite`; BigText description, following-leg distance, trip distance, duration, ETA and optional speed | Free edition uses the same upstream notification implementation. Korean phrases are upstream resources. Walking/bike and completion need device checks. Zero distance is not an arrival signal. |
| Organic Maps 2026.08.27-18-web | English walking `69 m` with empty street; French driving `380 m` with `Quai de l'Hôtel de Ville`. Category `navigation`, ongoing, channel `NAVIGATION`, bitmap maneuver, no ETA or RemoteViews | Play package shares the upstream service. Walking/car/bike use the same fields. Source removes the notification at completion; no arrival sentence is supplied. |
| maps.me 17.12.72038-googleRelease | English background driving `430 m`, French `150 m` then `64 m`, street `Rue de Lobau`; ongoing `ActiveNavigationChannel`, bitmap maneuver, no ETA/RemoteViews | APK `NavigationNotificationService`, its mappers and `NavigationModel.configureNotifications` confirm the format and restrict posting to background car/bike. Walking/transit are excluded. Bike and continuous background updates need device checks. |
| Yandex Maps 30.9.1 | Moscow driving English `150 m`, Russian `40 м` / `20 м`; street, expanded `timeOfArrivalView` (`04:28 PM` / `16:29`). Both `foreground_notification` and `bg_notification` observed. Walking exposed only `Navigator is running`, with no guidance fields | No maneuver words in driving notifications: neutral glyph, no inferred imminent/arrival. Cycling/transit unverified. Switching locales during a trip can leave street names and trip-summary units in English. |

The OsmAnd and Organic Maps pinned source links are in [README.md](README.md).
Yandex does not offer French in its documented
[app languages](https://yandex.com/support/m-maps/en/configure-app).
For the three bitmap-only sources, even a close turn remains `route`. Neither
street names nor traffic-light countdowns are maneuver evidence. Two successive
bitmap maneuvers onto the same street cannot be distinguished reliably.

## Reproduction notes

- OsmAnd: download Ile-de-France, route from Notre-Dame to Hotel de Ville,
  move GPS along Rue d'Arcole / the quay with `adb emu geo fix`. Captured the
  300 m to 15 m countdown. Locale changes required reopening the app.
- Organic Maps: GitHub's APK is `app.organicmaps.web`, not `app.organicmaps`.
  Download the overview and Paris maps; allow location and notifications.
- maps.me: APKPure's unqualified latest URL selected old 12.3.2-Huawei, whose
  Paris map download failed. Explicit Google version 72038 / arm64-v8a splits
  worked. Disable picture-in-picture and leave the app to trigger its
  notification service. The API 36.1 log warns about background location access;
  do not assume continuous updates from the successful individual captures.
- Yandex: walking from central Moscow worked but provided only a service title.
  Driving from Teatralnaya Square to Tverskaya Street provided the richer layout.
  A helper inflated the expanded RemoteViews, using the same approach as
  `NavNotificationReader`; no OCR or bitmap interpretation was used.
- Several early runs ended in native host QEMU `0xc0000005` crashes. Cold starts
  without snapshots eventually allowed the captures. No SDK, Gradle home,
  dependency cache or `local.properties` was changed.

## Phone and glasses check (about ten minutes)

1. Install the debug Navigation APK, approve it in the phone hub and allow
   Notification Access. Connect glasses with both hubs at least 1.5.0.
2. Start an OsmAnd demo/public route. Check distance, street, ETA, a textual
   turn arrow and one urgent beat around 40 m; a straight step stays nonurgent.
3. Start Organic Maps, maps.me and Yandex driving in turn. Check neutral glyph,
   distance/street, and Yandex ETA. For maps.me disable picture-in-picture and
   leave the app; check that values continue updating on this phone.
4. Switch each source off while guiding, then on: its activity must disappear
   and resume. Stop navigation and check removal. Do not expect an explicit
   arrival card from sources that only remove their notification.
5. If a mode is blank, capture its actual notification at a public location:
   package/version, locale/mode, channel/category/ongoing, title/text/subText/
   bigText, and visible expanded RemoteViews IDs/text at far/close/arrival steps.
   A generic service title or screenshot of an arrow alone is insufficient.
