# Review repairs for 1.0.2

Base: `2a039f16447feb53c8e01c46cc27b7ece158022b` (1.0.1).

## Behavior changes

| Area | Result |
| --- | --- |
| Deleted navigation targets | Single, batch, and folder deletion leave no target until explicit selection. Adding, importing, restoring, or restarting cannot silently choose a replacement. |
| Route waypoints | Each generated point records its owning route and vertex index. Regeneration replaces only that route's points in one transaction and preserves surviving vertex IDs. Manual points and older points without ownership remain untouched. |
| Pocket guide | Synchronization waits for stored waypoints and settings. Activity startup placeholders cannot stop a surviving guide or reset its declination. Explicit target and course changes still update a running guide. |
| Billing | A connection that never answers expires after ten seconds. Retry creates a fresh attempt; obsolete callbacks and timers cannot change the replacement attempt. |
| KML | Mixed and nested MultiGeometry children retain their own geometry and coordinates. Unsupported polygon holes and excessive nesting fail explicitly. |
| Track restore | Complete, synced staging files are atomically linked into place without replacing an existing destination. Identical completed files from interrupted batches can be reattached on retry. |
| Field tools | Ray solvers reject nonfinite or unsupported inputs. Azimuth fields preserve entered text and show errors for invalid degree/mil values. |
| UTM | UTM and grid convergence use NGA's zone selector, including exact Norway/Svalbard boundaries. |
| Terrain | Great-circle sampling follows the short arc across the dateline; elevation and contour tile coverage wrap consistently. |
| Elevation downloads | Areas over 400 tiles are rejected before downloading. Smaller requests report expected, cached, and failed tile counts; partial coverage is never reported as complete. |

## Review correction

The earlier NaN-paste crash scenario was not reachable through the existing field
widget: its character filter removed the pasted letters. The math functions did
accept invalid nonfinite inputs from callers. The changes harden those functions
and replace silent text rewriting with explicit UI validation; they do not claim
to reproduce that earlier UI crash scenario.

## Compatibility

Waypoint backup additions are optional; older backups still load. Legacy route
points are not assigned ownership by matching their names, because a manual point
can have the same name. Regenerating an old route may therefore leave its old
unowned points alongside newly owned points; users can remove the old set after
checking it.

Restoration still refuses to overwrite a different existing track file. This
includes truncated final files left by older app versions: their ownership cannot
be safely inferred. The new publication method prevents creating partial final
files going forward; it does not silently repair or delete pre-existing conflicts.

## Validation

Focused JVM regression tests cover repository deletion and regeneration, backup
ownership, deferred guide data, billing attempt ownership, KML geometry nesting,
interrupted track publication, coordinate validation and zone boundaries, and
great-circle/tile download behavior.

The local Gradle invocation was blocked before compilation because this workspace
could not reach `services.gradle.org` to obtain Gradle 8.11.1. The connected GitHub
integration also refused source publication with HTTP 403, so no pull request or
CI run was created. Compilation and the tests remain unverified. After the patch
is pushed, GitHub Actions is the build and test gate, including debug tests/APK
and unsigned minified release APK/AAB.

Device checks still needed: recreate the activity while pocket guidance runs with
manual declination; edit/delete/select targets during guidance; exercise Play's
unavailable-to-retry flow; restore tracks on supported Android internal storage;
and inspect dateline terrain/download messages on the map. JVM tests do not
substitute for service lifecycle, Play integration, or device smoke testing.
