# PoseBenchmark — Product Requirements Document

| Field | Value |
| --- | --- |
| Document status | Source-derived baseline with proposed follow-up requirements |
| Review date | 2026-09-07 |
| Platform | Android, minimum API 24; target API 37 |
| Current app version | 1.0 |
| Product owner | Not assigned in the repository |
| Implementation reference | [README](README.md), [MainActivity](app/src/main/java/com/example/posebenchmark/MainActivity.kt), [SkeletonOverlay](app/src/main/java/com/example/posebenchmark/SkeletonOverlay.kt) |

## 1. Problem and purpose

Developers evaluating on-device pose estimation need to see whether a person is detected, whether landmarks visually align with the camera image, and how quickly results arrive on a specific Android device.

PoseBenchmark currently provides a focused live viewer for the MediaPipe Full model running on CPU. Its product purpose is inferred from its name, code, and displayed metrics. It is a prototype baseline; no stakeholder-approved roadmap, measured performance targets, or release evidence exists in the repository.

This document distinguishes implemented behavior from proposed work. “Implemented” means present in source, not verified on a device. Proposed requirements are recommendations for the next iteration and are not existing features or delivery commitments.

## 2. Users and use cases

| Intended user | Need | Current support |
| --- | --- | --- |
| Android / ML developer | Verify the live camera-to-pose integration | Preview, pose skeleton, status, error logs |
| Performance evaluator | Observe throughput and latency on a device | Live callback FPS and latest callback latency; manual observation only |
| QA tester | Exercise permission, camera, rotation, and lifecycle behavior | Single-screen flow; no automated scenario coverage |

Primary user story: As a developer, I want to open the app, grant camera access, and see a person's skeleton and live processing metrics so I can assess basic integration and responsiveness on my device.

## 3. Goals and scope

### Current goals

1. Run the bundled Full pose model on-device with the CPU delegate.
2. Visualize the first detected person's landmarks over the rear-camera preview.
3. Show observable result throughput, pose presence, landmark count, and latency.
4. Keep camera analysis off the UI thread and bind camera use to the activity lifecycle.

### Current exclusions

The implementation does not support comparative benchmark sessions, GPU selection, model selection, multiperson visualization, front-camera switching, saved results, camera recording, uploaded processing, user accounts, exercise coaching, or pose accuracy scoring. It makes no clinical or biomechanical assessment.

### Success definition

The baseline is ready for evaluation when a reproducible build launches on a physical device, permission and camera flows work, pose appearance/disappearance updates the overlay, metrics match their documented definitions, and lifecycle transitions do not crash. These are acceptance goals, not completed validation claims. Numeric speed or accuracy thresholds remain unset until representative devices and measurement conditions are agreed.

## 4. User journey and states

1. The user launches the app and sees a camera/overlay surface with `MediaPipe Full` and `Loading model...`.
2. Model initialization starts in the background. Camera permission is checked independently.
3. If permission is missing, the system prompt requests it. Granting starts the camera; denial produces a toast with no dedicated retry flow.
4. Successful model initialization sets `Waiting for pose...`. The app uses the default rear camera.
5. Results update the skeleton or clear it. Approximately once per second, a result callback refreshes the status panel.
6. Closing the activity schedules model closure and shuts down the analysis executor.

| Condition | Current response | Gap |
| --- | --- | --- |
| Permission denied | Toast | No persistent explanation or retry/settings action |
| Model creation fails | Error status and Logcat entry | No reload control |
| Camera binding fails | Toast and Logcat entry | No persistent camera-error state |
| No person detected | Overlay clears; status eventually reports `NO` and zero landmarks | Status only refreshes when callbacks arrive |
| Conversion/inference error | Logcat entry | Display can remain stale |
| Device lacks rear camera | Camera startup failure | Manifest allows installation without camera hardware |

## 5. Functional requirements

Acceptance criteria below define how to verify the source-derived baseline. They have not been executed as a complete acceptance suite.

| ID | Requirement | Source status | Acceptance criteria |
| --- | --- | --- | --- |
| FR-01 | Request camera permission before camera startup | Implemented | Fresh launch requests permission; grant starts preview; denial displays the existing message without proceeding to camera binding. |
| FR-02 | Display the rear-camera preview | Implemented | A device with an available rear camera displays a fit-center preview after permission is granted. |
| FR-03 | Initialize the bundled model on CPU | Implemented | Asset `pose_landmarker_full.task` loads with CPU, live-stream mode, maximum one pose, and all three confidence thresholds set to 0.5. |
| FR-04 | Analyze recent frames asynchronously | Implemented | Analysis uses RGBA and keep-latest backpressure; frames are copied and rotated before `detectAsync`; normal conversion and conversion-error paths close the image proxy. |
| FR-05 | Render the first detected skeleton | Implemented; alignment unverified | Nonempty results produce red points and green connections with fit-center offsets; bounds checks skip unavailable connection indices. |
| FR-06 | Clear absent poses | Implemented | The next callback with no landmarks clears the previously visible skeleton. |
| FR-07 | Display and log processing metrics | Implemented | After a callback interval of at least one second, the panel shows FPS, pose presence, landmark count, and latency, and a corresponding log entry is emitted. |
| FR-08 | Handle initialization and runtime failures | Partial | Model creation failure updates status; camera failure shows a toast; runtime errors are logged. Recovery and stale-result handling are proposed below. |
| FR-09 | Release inference resources on activity destruction | Implemented; lifecycle unverified | Repeated launch/close and background/resume testing finds no use-after-close failures or retained camera use. |

## 6. Measurement contract

The current display must be interpreted consistently:

| Metric | Calculation | Interpretation and limits |
| --- | --- | --- |
| Result FPS (`Pose FPS`) | `callbackCount * 1000 / elapsedRealtimeWindowMs` | Counts all callbacks, including no-pose results; resets after each status refresh. Does not measure camera capture or rendering FPS. |
| Latency | `uptimeMillisAtCallback - submittedTimestampMs` | Timestamp is sampled before bitmap preparation; measures preparation and asynchronous processing through callback handling, excluding final presentation. Latest sample only. |
| Pose presence | `result.landmarks().isNotEmpty()` | Boolean from the refresh-triggering result. |
| Landmark count | First pose's landmark list size, otherwise zero | Does not report landmark accuracy or confidence. |

There is no warm-up exclusion, controlled input resolution, recorded sample set, aggregate latency statistic, dropped-frame accounting, or defined observation duration. The first FPS window includes time between model creation and the first results. No-callback conditions do not trigger a status refresh. Cross-device comparisons require a separate measurement protocol.

## 7. Technical constraints and quality requirements

| Area | Existing constraint | Evaluation requirement / gap |
| --- | --- | --- |
| Compatibility | Minimum API 24, compile/target API 37; camera hardware optional in manifest | Validate on selected physical devices; decide intended behavior without a rear camera. |
| Responsiveness | Model setup and frame analysis share one background executor | Verify a responsive UI during initialization and sustained inference. |
| Rendering | Manual fit-center mapping; no shared CameraX viewport configured | Verify image/overlay agreement across rotation, aspect ratios, and device camera configurations. |
| Threading | Overlay publishes a copied frame snapshot; `MainActivity` still shares mutable latest-frame dimensions | Associate dimensions with each result and validate lifecycle/thread ownership in follow-up work. |
| Memory | Per-frame bitmap preparation and rotation | Profile sustained allocation and cleanup before establishing performance targets. |
| Reliability | Errors mostly logged; no recovery or watchdog | Add explicit recoverable failure/stale-result behavior before broader evaluation. |
| Privacy | In-memory processing; no application upload or recording implementation | Preserve this behavior unless a separately specified persistence/export feature is added. |
| Accessibility | Status text exists, but strings and several pixel dimensions are hardcoded | Validate readable text, contrast, font scaling, screen readers, and system insets. |
| Maintainability | Most behavior is in one activity; template tests remain | Establish meaningful coverage and isolate measurement/coordinate logic as needed. |

## 8. Analysis findings and risks

| Priority | Finding | Consequence / proposed response |
| --- | --- | --- |
| Resolved (2026-09-13) | Test source imports lacked corresponding module test dependencies | JUnit and AndroidX test dependencies are now declared using the existing catalog aliases. |
| High | A second `SkeletonOverlay` with the production fully qualified name lives in `src/test` | Remove or rename the duplicate as appropriate; replace it with tests of production behavior. |
| High | Current metric labels can be mistaken for capture FPS or pure inference time | Document their exact semantics now; improve labels and measurement boundaries in a future code change. |
| High | No recorded device validation or sustained-run evidence | Execute the acceptance matrix before describing the prototype as validated. |
| Medium | Asynchronous results use latest-frame dimensions; overlay publication was made atomic on 2026-09-13 | Associate geometry with each result; drawing now uses one copied frame snapshot. |
| Medium | Bitmap copy does not explicitly handle row stride; preview geometry is assumed | Validate padded frames and aspect-ratio/rotation cases, then use robust conversion/transforms if needed. |
| Medium | Frame preparation allocates bitmaps; image resource ownership is not explicit | Profile memory and verify safe resource lifetime around asynchronous detection. |
| Medium | Denial/runtime errors lack recovery; callback loss can leave stale UI | Add persistent error states, retry behavior, and stale-result clearing. |
| Medium | No fixed resolution, warm-up, or session protocol | Define reproducible conditions before comparing devices or delegates. |
| Medium | No project license or model provenance documentation | Establish applicable redistribution terms before release. |
| Low | Version catalog contains unused entries; dependencies also live directly in the module | Consolidate configuration when maintaining dependency versions. |
| Low | Backup rules and other resources are largely templates | Review backup policy if future versions introduce saved data. |

These are static-review findings and potential failure modes, not claims that every risk has been reproduced.

## 9. Proposed next iteration

The following backlog is proposed, with no assigned owner or schedule.

| ID | Priority | Requirement | Acceptance criteria |
| --- | --- | --- | --- |
| NR-01 | P0 | Establish build and test readiness | Debug build and meaningful unit tests pass from documented setup; duplicate overlay source is resolved; device smoke-test results are recorded. |
| NR-02 | P0 | Make measurement semantics clear | UI and docs distinguish result throughput and preprocessing-to-callback latency; controlled tests verify counting and time-window calculations. |
| NR-03 | P0 | Validate geometry and lifecycle | Agreed devices pass rotation, aspect ratio, background/resume, and repeated launch/close scenarios without misalignment or crashes. |
| NR-04 | P1 | Add persistent errors and recovery | Permission, camera, and model failures provide a useful next action; an agreed no-callback timeout clears stale results. Timeout value remains to be decided. |
| NR-05 | P1 | Add reproducible benchmark sessions | A session defines warm-up and measurement duration and records device, OS, app/build version, model, delegate, actual frame dimensions, callback count, and timing samples. |
| NR-06 | P1 | Summarize and export results | Completed sessions report result FPS and latency median/p95 with sample count; a user-triggered export includes configuration and excludes raw camera images by default. Format and storage policy remain to be decided. |
| NR-07 | P2 | Compare model/delegate configurations | User can select explicitly supported configurations; unsupported delegates produce an explicit outcome; result records identify the configuration actually used. |
| NR-08 | P2 | Improve accessibility and localization | User-visible strings use resources; agreed font-scale and screen-reader checks pass; controls/status respect system insets. |

P0 means baseline evaluation readiness; P1 means repeatable measurement and recovery; P2 means expanded capability. CPU/GPU parity, alternate model availability, and associated performance benefits are not assumed.

## 10. Validation and release gates

| Layer | Planned checks | Current evidence |
| --- | --- | --- |
| Build | Assemble debug with checked-in wrapper and documented environment | `:app:assembleDebug` completed successfully using Android Studio's bundled runtime to launch Gradle. |
| Unit | Metric windows, empty/nonempty result handling, coordinate mapping | Arithmetic template only; test dependencies not declared. |
| Instrumentation | Permission, startup, overlay state, lifecycle | Package-name template only; no functional coverage. |
| Device | Rear camera, pose entry/exit, portrait/landscape, permission denial, resume/reopen | Not executed during this documentation review. |
| Performance | Controlled sustained sessions on selected devices, with warm-up and repeat runs | No results or baselines checked in. |

Before a baseline release, complete FR-01 through FR-09 verification, resolve the test setup, record device/OS/build details and unresolved issues, and review model/code redistribution terms. Before a comparative-benchmark release, also agree the session protocol and verify exported statistics against their source samples. No delivery date or quantitative performance gate is set here.

## 11. Open product decisions

- Which physical devices and OS versions define the supported evaluation matrix?
- What observation duration, warm-up duration, repetition count, and thermal conditions should comparisons use?
- Should latency continue to include preprocessing, or should separate stages be exposed?
- Is controlled camera resolution required for the first benchmark-session release?
- Which model variants and delegates belong in the comparison scope?
- Should camera hardware become an installation requirement, or should camera-less devices receive a dedicated unsupported state?
- What export format, retention policy, product owner, and release license should be adopted?

## 12. Review scope and validation note

This document is based on review of both production Kotlin files, the bundled model's presence and size, all test Kotlin sources, Gradle build/configuration files, the manifest, resources, backup/keep-rule templates, and repository file inventory. The binary model was not independently evaluated for quality or provenance.

The initial `:app:assembleDebug :app:testDebugUnitTest` attempt could not start because the shell had neither `JAVA_HOME` nor Java on `PATH`. A follow-up using Android Studio's installed runtime completed `:app:assembleDebug`, then failed at `:app:compileDebugUnitTestKotlin` with unresolved JUnit imports, `Test`, and `assertEquals`. Unit tests therefore did not execute. Device tests and lint were not run. No application code was changed as part of this documentation task.
