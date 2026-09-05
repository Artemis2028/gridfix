# 0.9.30 release candidate checks

This candidate fixes terrain confidence, location freshness, pocket guidance,
recording and backup persistence, interchange formats, purchase acknowledgement,
and release gating. Existing subscription access and Google license testing remain
unchanged. GitHub builds do not upload or roll out a release in Play Console.

## Validation completed for this patch

- 150 JVM tests passed, with zero failures, errors or skipped tests (72 new cases).
- Debug APK, unsigned minified release APK, and release AAB built successfully.
- The AAB's embedded deobfuscation map matches the generated R8 mapping file.
- Workflow YAML, shell syntax and publication gates were checked locally. Hosted
  GitHub CI has not run: the connected GitHub integration rejected branch creation
  with HTTP 403 because it has read-only access.
- No physical-device testing or production signing was performed. The checks below
  remain necessary before distributing the candidate.

## Automated checks

Run `./gradlew testDebugUnitTest assembleDebug assembleRelease bundleRelease`.
CI requires tests and both build variants to succeed before publishing a main
branch build. It also checks that the AAB contains the R8 deobfuscation map.

Regression coverage includes LOS curvature and missing terrain, arrival freshness
and hysteresis, manual angle conversion, backtrack endpoint retention, backup
validation and file collisions, history ordering, GPX/KML round trips, and billing
acknowledgement retries. Android service, sensor, notification and document-provider
behavior needs the device checks below; JVM tests do not establish it.

## Device checks before distributing this candidate

- [ ] Install the release APK or Play-delivered release bundle and confirm cold
  launch, purchase/restore and existing data still work. Test successful and failed
  acknowledgement through Google license testing; Retry remains visible after
  access is granted. No new payment or reviewer-access mechanism was added.
- [ ] Deny location, grant precise location in system Settings, and return. Position
  and Navigate should start without force-closing the app. Repeat with approximate
  permission, disabled location and restored precise permission.
- [ ] Select a waypoint and enable pocket guide. Test lock/unlock, background/return
  and portrait/landscape while turning the phone's **physical top edge**. The
  notification identifies the target and its Stop action ends guidance.
- [ ] Verify the haptics: one short heartbeat = on bearing; two short taps = right;
  one long pulse = left; three taps = unavailable; two long pulses = arrival.
  Silence does not confirm a bearing. With stale location or an unreliable compass,
  the unavailable cue must replace directional guidance. Test with notifications
  denied as well as allowed, and confirm that leaving the app's task stops guidance.
- [ ] Verify screen-locked guidance on more than one supported Android version and
  device, including a longer walk and battery-saving conditions. Confirm Stop leaves
  no continuing pulses or foreground notification.
- [ ] Approach a waypoint with a fresh accurate fix. Arrival requires the location
  accuracy circle to fit inside 50 m. A stale cached fix must not announce arrival
  or score a course point. Course points retain their stricter 25 m radius.
- [ ] Record, stop and save a track; then restart a recording. Exercise low-storage
  failure: recording must stop with an explicit message, preserve previously saved
  points, and permit another recording after storage is available.
- [ ] Restore a pre-0.9.26 backup (no `visible` fields), then export and restore a new
  backup twice. Records must remain visible by default and must not duplicate.
  Older practice results must not displace newer ones.
- [ ] Restore a backup with a malformed late section. No records should change.
  A write failure during application must report completed portions honestly;
  restore spans multiple stores and is not a single atomic transaction.
- [ ] Backtrack a winding track with more than 64 significant points. Both endpoints
  must survive. A finite point budget still approximates intermediate geometry.
- [ ] Import a multi-segment GPX with timezone offsets and a rejected coordinate;
  segments must not acquire connecting legs or overwrite another point's metadata.
  Round-trip a closed KML route, range ring and sector.
- [ ] Try sending a saved crash report with no mail app. The report stays available,
  and Copy works. A failed or unavailable backup destination must not report success.
