# Simulation episode recorder

`createRecorder(bus, root, world?)` in `src/data/recorder.ts` appends controls to the existing Experiments area after HUD initialization. Pass the world handle to enable head-camera snapshots. The recorder subscribes to actual Bus traffic; it does not generate sensor readings or engine decisions.

## Run and save

From the repository root, prepare and run the normal world/real-engine path:

```sh
(cd phone && ./gradlew :cli:installDist)
(cd world && npm ci && npm run build)
python3 tools/sim/world_bridge.py --serve world/dist
# Open http://127.0.0.1:8765/
```

For development, run `python3 tools/sim/world_bridge.py` in one terminal and `npm run dev` from `world/` in another. Play a course with the bridge connected to record meaningful engine as well as sensor events. No engine output is invented when the bridge is offline.

- Select the current or a retained completed episode, then **Download episode JSONL**.
- **Snapshot head camera dataset** captures an actual rendered 640 × 480 head-view JPEG at requested quality 0.85. Save **both** resulting links: JPEG and metadata JSON. Explicit links avoid browser multiple-download blocking. Only the latest snapshot is retained in the page; taking another replaces its links.
- Course changes, backwards course time, and explicit restart/reselection at time zero start new episodes. Pausing does not end an episode: the simulated sensor and live engine continue running. A completed course remains the active episode until reset or course change.

## JSONL format and clocks

Every line is one JSON value, with a trailing newline:

1. `type: "metadata"`, `schema: "determine.episode/2"`, `source: "simulation"`, episode/course IDs, starting clocks and reset reason, engine provenance when observed, requested/actual tuning at start and latest observation, configured retention limits, limitations.
2. `type: "event"`: increasing `seq`, browser receipt `wallMs`, latest reported course `tS`, event name, `replayed`, and original payload in `data`.
3. `type: "summary"`: completion boundary (or null for an active download), observed/retained/evicted event counts and retention policy.

`wallMs` is `Date.now()` in Unix milliseconds; wall-clock adjustments can move it backwards. `tS` is course seconds, updated by the world's course events (currently about 10 Hz), and is **not** an exact capture timestamp or the engine clock. It stops while paused and resets between episodes. Original `sensor.simMs` is the world's monotonic elapsed browser time in milliseconds; bridge `ms` is engine-relative milliseconds and `played.hostMs` is the bridge host clock. They are retained unchanged, not synchronized or interchangeable. Event `seq` preserves receipt order. Replayed initial samples have a new recorder receipt timestamp, not their original receipt time; tuning provenance marks unknown original observation times as null.

Recorded payloads include course, pose, raw sensor, link, tuning requests and observations, and bridge hello/status/state/alert/audio/cueTx/played/diag (also speak/vibrate/systemOff). Bridge text logs and streaming camera JPEGs are not retained. Tuning requests are not confirmations: actual tuning comes only from connected bridge hello/status, is null when unknown/external or disconnected, and its provenance is included. Sensor events prove browser observations, not successful engine delivery; bridge events are separately observed evidence.

## Bounded retention and snapshots

Configured limits: one active plus **3 completed episodes**; each retains at most **12,000 events** and **4,000,000 JavaScript string characters** of event JSON. Oldest events are evicted first, and an event larger than the character cap is omitted; all omissions increment `droppedEvents`. These are configured bounds, not measured memory usage. Metadata and summaries remain even after event eviction. Nothing is persisted by the recorder across reloads. Snapshot URLs are replaced/revoked on the next successful capture; only one capture can be in flight.

Snapshot `determine.snapshot/1` metadata identifies its JPEG, capture start/finish wall clocks, course at start/completion, latest raw sensor and pose with their own receipt clocks, and tuning. Samples are not synchronized with the JPEG; null means none received in that episode. Capture can overlap a course change, which is exposed by the two course fields.

**`truthM` covers only the single sensor cone, up to the model's 4 m truth search. It cannot label all visible objects.** The JPEG has no fabricated full-camera object labels, segmentation, depth map or collision truth. The sensor models one ~18° zone with a nominal white-target range up to 1.2 m; this is simulation, not hardware validation. NO_TARGET or silence never means a clear path. Exports are **not ADT/VRS**, acoustic/Bluetooth timing measurements, or validated safety metrics.

Pure exports `serializeEpisodeJsonl(episode)` and `serializeSnapshotMetadata(metadata)` support integration smoke checks without DOM or a running bridge. No recorder verification command was executed while implementing this module; integration owns typechecking and live-download/reset checks.
