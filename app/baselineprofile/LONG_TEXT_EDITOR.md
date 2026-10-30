# Long text editor measurements

Use a dedicated physical device and the **benchmarkRelease** variant (profileable, non-debuggable,
Release optimizations). Do not use debug or emulator frame timings to claim a performance improvement.
The form fixture Activity exists only in `app/src/benchmarkRelease`; normal release APKs do not expose it.

```powershell
.\gradlew.bat :app:baselineprofile:connectedBenchmarkReleaseAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.LongTextEditorBenchmarks"
```

The parameterized suite covers 10,000 and 50,000 UTF-16 code units, with paragraphs and without line
breaks, the actual chat input/fullscreen editor, and the production TextArea/fullscreen editor.
Fullscreen cases run with search both off and on. The text includes CJK, Latin text and emoji; no message
is sent and the form fixture does not save any assistant settings. Use a test installation because the
chat cases replace its current draft. Disable automatic network activity/streaming for both runs.

`FrameTimingMetric` emits frame CPU time and frame overrun distributions (including P95/P99 on supported
Android versions), plus Perfetto traces. `queryResponse` records `editor_search_response`, including
the 150 ms debounce, worker search and result publication. Inspect FrameTimeline missed deadlines in
the exported traces for the jank count. Record the device, Android version, refresh rate, font scale,
IME, thermal state and blur/glass setting alongside the results. Keep those fixed across both revisions.

Capture baseline and candidate on the same device. The original revision lacks search and the new form
fixture, so backport only the benchmark fixture/tags when measuring it; compare search-off scrolling
against baseline and search-on against search-off on the candidate. Save results outside the checkout
before changing revisions. Do not overwrite or reset a working checkout to collect a baseline.

Acceptance: no full-document search/copy work during scrolling; only one editor observes an active
draft; search and scrollbar additions do not significantly regress frame timings. Report inline and
fullscreen separately. Remaining Compose text-drawing cost must be reported rather than attributed
to a successful fix without measurements.

No physical-device measurements were available in the implementation environment.
The checkout's existing Google Services configuration also has no matching release application client.
Kotlin compilation of the benchmark fixture was checked with
`-x :app:processGithubBenchmarkReleaseGoogleServices`; this is a source compilation check, not a runnable
release validation. Configure the normal release Firebase client before executing the device suite.
