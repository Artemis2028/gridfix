# Review repairs for 1.0.3

Base: `e196f6b4d3f171835da32d7185cfb3a6635e3532` (validated 1.0.2).

## Behavior changes

| Finding | Result |
| --- | --- |
| F1: restored route ownership collisions | Restore deduplicates both UUIDs and route/vertex ownership. Current records win. Regeneration preserves the selected owner and retains older duplicate UUIDs as standalone points. Stale selection requests cannot reselect a deleted UUID. |
| F2: course navigation lock | Every navigation entry point resolves to the active checkpoint. Target pickers are disabled during a course, and a running pocket guide follows the same checkpoint. Course run and checkpoint guards reject obsolete scoring callbacks. |
| F3: missing course checkpoints | Removing any remaining checkpoint blocks scoring and stops guidance. The UI identifies missing checkpoint numbers and offers restoration or ending the course. Restoring the original IDs resumes progress; elapsed time continues while blocked. Completed runs are finalized from stored state even if the scoring effect is cancelled. |
| F4: reordered billing responses | Product queries own their callbacks and deadlines. An older empty/error response cannot replace a successful newer Retry on the same BillingClient. A late response can recover its own timed-out query only if no replacement query exists. |
| F5: dateline projection | Forced-zone UTM wraps longitude differences around the central meridian; inverse results use canonical longitudes. Nearby polygon vertices and bearing rays can cross the dateline on one projection plane. |
| F6: route cards and PDF sketches | Screen, text, and PDF share leg calculations. Forward bearings use the start's north reference; return bearings use the end's reference and the reverse geodesic. Sketches unwrap each leg's longitude to preserve direction across the dateline. |
| F7: historical GPX track times | Track timestamps before 1970 become unknown (`0`), matching the backup format. Reads of previously saved logs also normalize negative times without modifying the source files. Waypoint historical metadata remains intact. Direct track imports validate all points before persistence. |
| F8: oversized data-package entries | Oversized entries reject the whole parse before persistence, with the entry name and limit shown to the user. Nested KMZ and ignored entries share limits of 32 MiB per entry, 128 MiB total decompressed bytes, and 2,048 entries. |
| F9: failed import retries | A validated plan assigns stable IDs to unchanged parsed data. Track files are staged before metadata publication; subsequent waypoint and graphic phases report confirmed progress. Retrying adds missing records and preserves already-imported edits. |

## Compatibility and recovery

Backup fields for route ownership remain optional. Legacy points are never assigned
ownership by their names. When legacy records already share an owner/vertex pair,
regeneration retains the selected record as the generated point and clears ownership
on the others without deleting their UUIDs.

A backup's old route UUID is skipped if a current generated UUID already owns that
vertex. A course referencing the old UUID stays blocked; it does not silently adopt
the replacement. Restore the original missing checkpoint before regenerating that
vertex, or end the course and start another. Deleting an already visited checkpoint
does not block the remaining course.

Import identity depends on parsed content and record order, not the import time or
current folder spelling. Reimporting an unchanged file preserves existing edits;
deleting a previously imported record and importing again recreates that missing
record. Changed or reordered parsed content is a different import. Identical entries
within one file remain distinct. Imports made by older versions used random IDs, so
this version cannot reliably deduplicate them against the original file.

The import is resumable, not a transaction spanning every repository. Each committed
phase remains if a later phase fails. Cancellation propagates after the current
commit completes. No compensating rollback deletes existing or edited data. Track
publication still refuses to overwrite a different file with the same ID; older
truncated/conflicting files are not silently removed. Incoming backups still reject
negative track timestamps; normalization applies to interchange and existing logs.

## Validation

New JVM regression coverage exercises actual waypoint, graphics, and course
repository transactions; route ownership and stale selection; course deletion,
restoration, run identity and completion; billing response/deadline ownership;
dateline projection and route bearings/sketches; GPX/backup compatibility; nested
archive limits; and import failures, retries, identity stability and cancellation.
Existing track staging/publication tests complement the coordinator tests.

The base 1.0.2 source passed JVM tests, debug builds, and signed minified release
APK/AAB builds in GitHub Actions run `35628105020`. The 1.0.3 changes have received
static and independent cross-review, but have not compiled locally: this environment
cannot download the Gradle distribution. The delivered workflow runs the complete
JVM suite, debug build, signed minified release APK/AAB builds, and mapping checks
before it commits the embedded source or publishes a release. Installer validation
is separate from Android compilation and must not be reported as a passing build.

Device checks after CI:

1. Run a course with pocket guidance, then try selecting another point from Map,
   Waypoints, and Navigate. Confirm both navigation and guidance stay on the current
   checkpoint. End the course and confirm free target selection returns.
2. Remove the current or a future checkpoint, including by shortening a route.
   Confirm blocked guidance/scoring, useful missing-point text, and recovery when
   the same UUIDs return. Check that elapsed time includes the blocked interval.
3. Delete and regenerate a route vertex, restore an older backup, and regenerate
   again. Confirm the live vertex UUID and selection survive.
4. Retry billing while an earlier request is delayed, then deliver the old failure.
   Confirm the newly loaded plans remain available.
5. Compare a dateline-crossing route's card and PDF in true, magnetic, and grid
   north modes. Confirm forward/return references and sketch direction.
6. Import a GPX with historical dates and export/restore a whole backup. Import a
   mixed package with an oversized supported entry and confirm nothing is added.
7. Interrupt a multi-type import after a committed phase, retry the unchanged file,
   and confirm no duplicate records or lost edits.

The 1.0.2 repairs remain in place, including authoritative guide synchronization,
guide command ordering, billing connection attempts, KML geometry handling,
atomic track publication, coordinate validation, NGA zone selection, and wrapped
terrain/elevation coverage. The earlier review's NaN-paste crash claim was corrected:
the old widget filtered the text; the underlying math validation was the real gap.
