# MGRS GPS

An offline-first MGRS land-navigation app for Android. Built for people who are
taught to navigate with a map, a compass and a pace count, and who want the grid
in their pocket to agree with the one on the sheet.

**Status: 0.9.30 release candidate** — in closed testing on Google Play. 1.0 is the store launch.

Package `app.gridfix.android` · repository `gridfix` (the original working name;
the product is **MGRS GPS** everywhere a user can see it).

## What it does

- **Position** — your grid at 4 to 10 digits, in one of three faces: a plain
  Glance readout, an issued-pattern lensatic dial, or a clean compass card.
  Fix quality is graded in plain words (EXCELLENT to DEGRADED, plus STALE and
  NETWORK) with the finest MGRS precision that fix actually supports.
- **Navigate** — azimuth, back azimuth, distance and time to a waypoint, with an
  eyes-free haptic guide and an arrival buzz.
- **Map** — MGRS grid overlay down to 10 m, offline basemaps (browse cache, USGS
  area download, or your own MBTiles), NATO symbols, tactical control measures,
  ruler, elevation, line of sight, viewshed and contour lines.
- **Waypoints** — folders that toggle on and off as one overlay, tracks, routes,
  route cards, practice courses, GPX / KML / ATAK import and export, backups.
- **Field tools** — resection and intersection, sun and moon times, declination
  diagram, pace count, strip-map PDFs.

Everything works with no signal and no account. Nothing is uploaded anywhere.

## Not a primary means of navigation

A phone GPS is an aid. Carry a map and a compass, know your pace count, and
confirm every grid against the ground. Coverage and accuracy of the open map and
elevation data vary by country, and contours are modelled rather than surveyed.

## Building

```
./gradlew assembleDebug
```

JDK 17. The Android SDK comes from `ANDROID_HOME` or `local.properties`.

Map tiles fall back to community sources when no key is present. To build with
MapTiler basemaps, set `MAPTILER_KEY` in the environment before building; the
key is compiled into `BuildConfig` and should be restricted by package name and
signing certificate in the MapTiler dashboard, since anything in an APK is public.

Release builds are minified by R8 and need signing config in the environment
(`GRIDFIX_KS`, `GRIDFIX_KS_PASS`). CI publishes `mapping.txt` with every build so
a Play crash report can be de-obfuscated.

Unit tests cover the field math — MGRS round trips, zone exceptions, ray fixes,
angle wrap, folder naming, twilight ordering:

```
./gradlew testDebugUnitTest
```

## How releases are made

CI builds every push to `main` and publishes the debug APK to the `latest`
release, plus a signed AAB and a minified release APK when the keystore secrets
are present. Build status is mirrored to the `ci-status` branch.

Source changes are delivered through a bootstrap payload in the workflow file,
which is applied exactly once per version and then skipped (`.github/last-payload`
records which payload has landed). Edit the Kotlin in git, not in the workflow.

## Third-party

MGRS conversion by the NGA MGRS library (MIT). Map engine osmdroid (Apache 2.0).
QR codes by ZXing (Apache 2.0). Map data © OpenStreetMap contributors (ODbL),
OpenTopoMap (CC-BY-SA), USGS, MapTiler. Elevation from Terrarium tiles via AWS
Open Data (SRTM, USGS 3DEP/NED, GMTED2010, ETOPO1). Fonts: Saira Semi Condensed,
Fira Mono and Antonio (SIL Open Font License).

## License

MIT — see [LICENSE](LICENSE).
