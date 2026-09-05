# 0.9.31 MilGPS interoperability candidate

Includes the 0.9.30 fixes and adds preservation of MilGPS waypoint metadata.
Import a GPX file using Waypoints > Import. Points appear in the Imported folder;
identical names or coordinates do not combine separate records. Importing a file
again adds another copy, so use a temporary folder for repeated testing.

The symbol encoding is documented in [MilGPS's format guide](https://milgps.com/userguide/frequently-asked-questions/csv-format/).
Color is independent of military affiliation. Import never infers hostile,
friendly or neutral status from a color or from these basic geometric shapes.

| Code | Shape | Character |
| --- | --- | --- |
| 0 | Cross | None |
| 1000 | Circle | None |
| 2100 | Triangle | 0 |
| 3200 | Square | ! |
| 4112 | Star | C |
| 1106 | Circle | 6 |

Supported appearance includes the documented five shapes, 0-9/A-Z/!/? characters,
and red/orange/yellow/green/blue/cyan/magenta. RGB styling follows Gridfix's marker
renderer; no pixel-identical match to another app's display is claimed. Night mode
still renders markers in red. Unknown numeric codes and color names are retained
for GPX re-export, with a fallback display rather than an invented interpretation.

Waypoint elevation and original recording time are optional metadata, separate
from the time a record was imported into Gridfix. Missing values stay absent,
including in GPX export. The metadata survives repository storage and backup.
Legacy backups and existing waypoints do not need a migration. This update does
not promise equivalent custom styling in KML, CoT or other export formats.

## Automated validation

- 165 JVM tests passed (15 added for MilGPS compatibility), with no failures,
  errors or skipped cases.
- Debug APK, unsigned minified release APK and release AAB built successfully.
  The release APK reports version 0.9.31 / version code 59.
- The AAB's embedded deobfuscation map matches this build's R8 mapping file.
- Validation used `testDebugUnitTest assembleDebug bundleRelease assembleRelease`
  with `-Pkotlin.incremental=false` to compile Kotlin without restored incremental
  state. The paste workflow uses the same setting for reproducible compilation.
- Hosted GitHub Actions, production signing and physical-device verification
  remain pending. The connected GitHub integration is read-only.

## Device checks

- Import a synthetic GPX with the six code/color combinations above. Confirm all
  six records exist even if their coordinates are identical (their map markers
  will overlap at the same location).
- Verify markers in the map, waypoint list, navigation target and dropdown.
- Edit a name or folder, save, close the app and reopen it: appearance, elevation
  and recording time remain. Edit Color/Shape/Icon in the imported marker section;
  selecting a cross removes the character. "Use Gridfix symbols" intentionally
  replaces the imported appearance while retaining elevation and recording time.
- Switch to a military unit or select an affiliation: the chosen Gridfix symbol
  and affiliation take over, without keeping an imported geometric marker on top.
- Export GPX, import it in MilGPS and verify all metadata. Then export and restore
  a Gridfix backup and verify the same data. Use disposable records for repeats.
- Check portrait, landscape, enlarged text and night mode. The existing 0.9.30
  screen-lock, billing and restore checks still apply before tester distribution.

Regression fixtures use synthetic coordinates and times. This update does not add
the user's field locations or screenshots to the source or paste workflow.
