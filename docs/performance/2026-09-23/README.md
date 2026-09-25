# Windows fixed-budget measurements, 2026-09-23

These are controlled application measurements, not a promise of an equivalent
speedup for every game or the user's daily configuration. **Defaults were not
changed.** The candidate passes the implemented numerical GPU/latency gates in
these samples, but the midgame heap observations below require follow-up before
an unconditional recommendation. Importing a report still requires fresh asset,
GPU/driver and command fingerprint matching, independent review and explicit
user confirmation. The restore action removes only the measured-scene overlay.

## Controls and identity

- Probe source: `b7ba99e5`, measurement contract version 2. This is the measurement
  branch's production application plus test-only instrumentation, not an estimate
  of the aggregate throughput effect of the catalog, queue and save changes.
- Windows 11 build 26200, Java 21, one NVIDIA GeForce RTX 5090, driver `591.86`,
  reported device memory `32607 MiB`; CUDA KataGo 1.18.2, source
  `47aadc08518b3e121f22539796c911002f699584`, B11 11500M model. The separate
  weight-upgrade PR is deliberately excluded.
- Both fixtures use 19x19, Tromp-Taylor rules and 7.5 komi. Live opening measures
  the position after 8 moves; live midgame after 80 moves. Whole-game measures
  the opening's 9 positions, including its root, not a full-length game.
- Minimum budget is **5000 root visits per position**. Ownership, move ownership,
  PV visits and PV length 100 are retained, with 10-centisecond GTP updates.
  Whole-game analysis uses final results, not intermediate progress updates.
- Each scene has one cold run per profile and three complete warm pairs. Warm
  order is candidate/baseline, baseline/candidate, candidate/baseline. Every warm
  process first searches the same budget, then clears search/NN cache before
  measurement. Profile/scenario disk caches are isolated. Cold runs do not enter
  recommendation statistics.
- Live controls: 8 search threads / batch 16 versus 16 threads / batch 32.
  Whole-game controls: 8 parallel positions x 2 threads / batch 16 versus
  12 positions x 2 threads / batch 32. These are experimental controls, not a
  statement that the current defaults equal the baseline.

| Identity | SHA-256 |
| --- | --- |
| Engine | `9a87f2e40233bb5694332546f9cad0a6248f4593341ccafb29225fbf025a6ef6` |
| Model | `73f6454eba62d2f6d099af8ce66d8c3fde6225e223c55817da0627590e98b0ae` |
| Primary config | `96e484242c1d4010a11efd6c15291bbc6c682faea5e37f9b25c0f5dcab498d39` |
| Opening fixture | `8096bd727ee61bec3a5a69f7876b57b4be2ac0680ebf275ba6aa11a57698971e` |
| Midgame fixture | `233563f257645101826b21be0ebc2a6b36feef4f0ac8dd9894671297025572b7` |
| Profile matrix | `cf8f1143a09a131efefa23f995d3445a971196a018b125ec8acf84920fe096b4` |

The fixtures/profile matrix are recorded in the measurement PR's
`scripts/fixtures/performance-opening.json`, `performance-midgame.json` and
`performance-profiles.json`. Exact launch arguments were captured from the
running OS process (Windows CIM fallback here), not inferred from the requested
configuration. Portable JSON below contains all effective concurrency values
and non-concurrency overrides. Only cache/log output locations are omitted.
In particular, visit limits and `analysisPVLen=100` remain in the fingerprint;
a daily command without those semantics will not match. No matching rule was
weakened to make the results applicable.

## Warm application results

Times below are seconds; latency columns are milliseconds. Each latency entry
is median / maximum across the three runs. EDT entries are the median / maximum
of each run's nearest-rank p95, not pooled per-event p95. Memory is sampled
**total-device** GPU memory in MiB, not the engine's private VRAM.

| Scene / profile | Median completion s | First result ms | Pause/cancel ms | EDT p95 ms | Peak sampled GPU MiB |
| --- | ---: | --- | --- | --- | ---: |
| Opening live / baseline | 5.023187 | 126.608 / 126.797 | 11.938 / 35.909 | 33.740 / 37.567 | 3075 |
| Opening live / candidate | 3.243131 | 117.657 / 126.441 | 13.246 / 38.533 | 32.942 / 37.132 | 3154 |
| Midgame live / baseline | 5.335713 | 122.995 / 128.640 | 9.597 / 9.944 | 35.818 / 36.290 | 3091 |
| Midgame live / candidate | 3.498426 | 127.977 / 129.015 | 12.479 / 13.512 | 41.365 / 45.466 | 3165 |
| Opening whole-game / baseline | 32.692480 | 16315.756 / 17154.905 | 5.483 / 15.129 | 0.0638 / 0.0645 | 3168 |
| Opening whole-game / candidate | 25.187583 | 17006.623 / 18495.656 | 5.266 / 5.516 | 0.0730 / 0.0781 | 3192 |

Lifetime device-utilization samples have a different scope from search timing:

| Scene | Median of baseline per-run utilization medians | Median of candidate per-run utilization medians |
| --- | ---: | ---: |
| Opening live | 74% | 43% |
| Midgame live | 73.5% | 5.5% |
| Opening whole-game | 83% | 87.5% |

These include engine startup, OS command inspection, fixture restoration, warm-up,
search and cancellation. The shorter candidate searches occupy less of the total
application lifetime, so the low midgame lifetime median does not mean search
was limited to 5.5% GPU utilization. Search-only utilization was not isolated;
no GPU-efficiency improvement is inferred from these lifetime medians.

| Scene | Paired baseline/candidate time ratios, rounds 1-3 | Median paired ratio | Timing CV baseline / candidate |
| --- | --- | ---: | --- |
| Opening live | 1.488145x, 1.588188x, 1.459524x | 1.488145x | 4.35% / 2.35% |
| Midgame live | 1.524978x, 1.659174x, 1.475710x | 1.524978x | 3.87% / 2.31% |
| Opening whole-game | 1.341520x, 1.303098x, 1.235046x | 1.303098x | 2.11% / 3.22% |

All three candidates complete faster in every warm pair and pass the documented
numerical gates. Timing CV is below 10%, so the predefined variation rule does
not require five rounds. Median paired ratios are not ratios of independent
medians. Live observed visits range 5004-5201 at opening and 5022-5135 at midgame;
every whole-game position reports 5001. Fixed minimum budgets can overshoot at
the streaming/polling boundary, and exact search contents can vary with threads.

Median observed live throughput is 1020.87 -> 1556.62 root visits/s at opening
and 941.39 -> 1467.80 root visits/s at midgame. Whole-game throughput is
0.275293 -> 0.357319 completed positions/s at the fixed per-position budget.
These are scenario-specific application measurements, not numbers from KataGo's
official benchmark on a different set of positions.

Midgame first-result median is 4.982 ms later, pause median 2.883 ms later, and
EDT p95 median 5.547 ms higher with the candidate. The increases remain inside
the existing median/tail tolerances but are not hidden by the completion-time
gain. Whole-game first final is also later, by 0.691 s at the median; that metric
is reported here even though it is not part of the current whole-game gate.

### Heap observation requiring follow-up

| Scene | Baseline used-heap snapshot median (range), MiB | Candidate used-heap snapshot median (range), MiB |
| --- | --- | --- |
| Opening live | 1124.63 (721.85-1311.88) | 547.25 (474.27-634.79) |
| Midgame live | 883.20 (856.35-1173.39) | 1926.11 (1833.54-3173.83) |
| Opening whole-game | 526.10 (497.73-564.48) | 490.78 (486.59-614.43) |

These are Java used-heap snapshots after the measured workload/cancellation,
not heap peaks, retained-live-set measurements or process RSS. Collection timing
was not synchronized and garbage collection was not forced. The substantially
higher midgame candidate snapshots are an unresolved memory signal: the current
GPU-memory numerical gate does **not** prove that application RAM usage cannot
regress. A controlled heap/RSS follow-up is required to resolve it. No forced-GC
or system-setting change was used to improve the reported figures.

## Cold observations, excluded from tuning

| Scene / profile | Application startup s | Separate whole-game engine startup s | First cold search s |
| --- | ---: | ---: | ---: |
| Opening live / baseline | 5.087173 | n/a | 4.943852 |
| Opening live / candidate | 5.199011 | n/a | 3.254780 |
| Midgame live / baseline | 5.350759 | n/a | 5.612941 |
| Midgame live / candidate | 5.197716 | n/a | 3.594433 |
| Opening whole-game / baseline | 2.060764 | 2.904731 | 32.648401 |
| Opening whole-game / candidate | 2.007434 | 3.588365 | 25.715999 |

Live startup ends when the application and main engine are ready. Whole-game
application startup ends with the window, before its dedicated worker loads;
worker startup is recorded separately. Search durations exclude preparation,
restore/cache barriers and OS command inspection. These scene-specific startup
scopes must not be treated as interchangeable engine readiness measurements.

## Same-day bare-engine controls

The same-day bare matrices use the same probe revision, engine/model/config and
fixture hashes, rule/komi/output controls and budgets. They also have three
alternating warm pairs per scene, with cold runs retained separately. Together,
the four complete matrices contain 48 recorded samples: 36 warm and 12 cold.

| Bare scene / profile | Median completion s | First result median / max ms | Pause/cancel median / max ms | Peak sampled GPU MiB |
| --- | ---: | --- | --- | ---: |
| Opening live / baseline | 4.688570 | 111.821 / 112.714 | 5.565 / 9.834 | 2916 |
| Opening live / candidate | 3.038990 | 123.079 / 123.831 | 9.325 / 10.089 | 3025 |
| Midgame live / baseline | 4.948376 | 115.018 / 118.347 | 7.009 / 7.319 | 2940 |
| Midgame live / candidate | 3.324073 | 113.888 / 116.700 | 8.374 / 10.143 | 3011 |
| Opening whole-game / baseline | 32.724567 | 16112.965 / 16217.051 | 56.307 / 60.320 | 2964 |
| Opening whole-game / candidate | 24.984364 | 16560.440 / 16955.860 | 55.455 / 56.621 | 3033 |

| Bare scene | Paired baseline/candidate time ratios, rounds 1-3 | Median paired ratio | Timing CV baseline / candidate |
| --- | --- | ---: | --- |
| Opening live | 1.607658x, 1.503418x, 1.542104x | 1.542104x | 3.33% / 0.09% |
| Midgame live | 1.515439x, 1.433689x, 1.488649x | 1.488649x | 0.64% / 3.39% |
| Opening whole-game | 1.284645x, 1.323657x, 1.298606x | 1.298606x | 1.63% / 0.57% |

Observed bare live visits range 5029-5164 at opening and 5075-5176 at midgame.
All nine whole-game positions again report 5001 visits. No warm pair is dropped.
The maximum visible engine count remains one. Maximum telemetry spacing in the
bare opening baseline reaches 2.097 seconds, so these sampled peaks are not
continuous memory measurements.

| Matched scene / profile | Application median s | Bare median s | Observed difference s |
| --- | ---: | ---: | ---: |
| Opening live / baseline | 5.023187 | 4.688570 | +0.334617 |
| Opening live / candidate | 3.243131 | 3.038990 | +0.204141 |
| Midgame live / baseline | 5.335713 | 4.948376 | +0.387336 |
| Midgame live / candidate | 3.498426 | 3.324073 | +0.174353 |
| Opening whole-game / baseline | 32.692480 | 32.724567 | -0.032087 |
| Opening whole-game / candidate | 25.187583 | 24.984364 | +0.203219 |

These differences are descriptive, **not a precisely isolated or universally
applicable application-overhead estimate**. Bare and application matrices were
run sequentially, not interleaved as one common paired experiment; thermals,
search randomness, streaming overshoot and background work can differ. The
slightly negative whole-game baseline difference is consistent with that noise.
The repeatable completion gains in both bare and application cases support a
benefit from the measured concurrency controls on these workloads, not proof
that the other UI/code optimizations make KataGo itself search faster.

Bare live response ends at the numbered GTP stop acknowledgment. Bare whole-game
response waits for every canceled JSON final result after confirmed active
search. The application instead cancels its dedicated worker and waits for OS
process exit; its much smaller cancellation number is a **different endpoint**,
not evidence of a tenfold improvement in the same cancellation operation.
Bare records contain no EDT measurement and cannot be imported as application
recommendations. The preceding 2026-09-22 exploratory controls are not mixed into
these same-day comparisons.

## Portable evidence

- [Opening live](live-opening.json): six warm observations, complete budget and
  latency values, fingerprint and concurrency parameters.
- [Opening whole-game](whole-opening.json): six warm observations and visits for
  all nine positions.
- [Midgame live](live-midgame.json): six warm observations for the 80-move fixture.

These are measurement records, not commands to execute. They contain no local
user paths, credentials or raw engine logs. The importer remains authoritative;
an exported JSON file or a numerical pass alone is not approval to apply it.

## Scope and limitations

- Application live timing covers ordinary last-move placement to first positive
  result and completion. Fixture restore confirmation precedes stop/cache-clear
  success and fresh payload clearing. The old `app-final-opening` version-1
  restore-timing dataset is invalid and excluded, as are smoke runs and cold
  values from all recommendation comparisons. Exports require contract version 2.
- Live response measures the application's pause and acknowledged barrier.
  Whole-game cancellation waits for an active, positive-visit cancellation
  workload and measures production worker process exit. It is not equivalent to
  bare JSON termination acknowledgment or all-final-response completion.
- GPU telemetry spans the entire child-process lifetime, including startup,
  warm-up and cancellation. It is sampled roughly once per second and can miss
  peaks or short competing workloads. Device utilization includes background
  desktop work; it is not isolated measured-search utilization.
- The maximum visible KataGo process count is one. The operator also checked
  that no other KataGo process was running. Five permission-hidden process names
  appeared in NVIDIA telemetry; their identities/work are unknown, not assumed
  KataGo or assumed harmless. WDDM per-process VRAM is unavailable.
- The result files preserve observed root-visit counters and per-position budget
  checks, but not every raw streaming rootInfo line. This is not a line-by-line
  protocol or numerical-output audit.
- Only two live positions and a nine-position opening workload were measured.
  This does not establish full-length-game behavior, Linux speed, all models,
  other GPUs, unmeasured settings, or precise memory peaks.
- Model quality, search budget, output content and numerical precision were not
  reduced. More threads can produce ordinary search randomness; identical output
  text or an unchanged winrate at every visit is not promised.
