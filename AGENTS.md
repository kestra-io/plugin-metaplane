# Kestra Metaplane Plugin

## What

- Provides plugin components under `io.kestra.plugin.metaplane`.
- Includes the tasks `Run`, `Get`, `List`, `Gate`, the trigger `MonitorResultTrigger`, the shared
  `AbstractMetaplaneTask` base class, and the response models `Monitor`, `MonitorStatusResponse`,
  `MonitorStatus`, `SeriesStatus`, `FailStrategy`.

## Why

- What user problem does this solve? Metaplane is a data-observability tool: teams need to run its
  SQL anomaly-detection monitors, read their results, and gate a data pipeline on the outcome without
  leaving Kestra.
- Why would a team adopt this plugin in a workflow? It lets a flow trigger a monitor run, wait for and
  read its result, and halt the pipeline (e.g. before loading data downstream) when an anomaly is
  detected, or react to status changes via a polling trigger.
- What operational/business outcome does it enable? It closes the loop between data-quality checks and
  pipeline execution, so bad data is caught and blocked automatically instead of relying on someone
  checking a dashboard.

## How

### Architecture

Single-module, flat plugin (no sub-packages). Source package: `io.kestra.plugin.metaplane`.

All tasks and the trigger authenticate with a Bearer `apiToken` against the Metaplane API
(`https://docs.metaplane.dev/reference`), served from `https://dev.api.metaplane.dev` (not
`app.metaplane.dev`, which is the web app); the base URL stays user-overridable in case Metaplane
changes or adds hosts. No official Java SDK exists, so calls use Kestra's internal HTTP client
(`io.kestra.core.http.client`) and Jackson mappers from `io.kestra.core.serializers`.

### Key Plugin Classes

- `io.kestra.plugin.metaplane.AbstractMetaplaneTask` — shared apiToken/baseUrl properties, HTTP
  request plumbing, and error handling (401/403 → invalid token, 404 on the status endpoint → "monitor
  has no run history yet"). Exposes static helpers reused by `MonitorResultTrigger`, which cannot
  extend it since it extends `AbstractTrigger` instead of `Task`, and by `Gate` (`enqueueMonitors`,
  shared with `Run`).
- `io.kestra.plugin.metaplane.Run` — enqueues one or more monitors to run now (`POST /v1/monitors/run`),
  via the shared `AbstractMetaplaneTask.enqueueMonitors` helper.
- `io.kestra.plugin.metaplane.Get` — reads a monitor's latest status (`GET /v2/monitors/status/{id}`),
  a pure read task; gating is left to the flow. Output includes the per-series breakdown (`series`).
- `io.kestra.plugin.metaplane.List` — lists monitors for a given connection
  (`GET /v1/monitors/connection/{connectionId}`), with `fetchType` semantics.
- `io.kestra.plugin.metaplane.Gate` — synchronous quality gate: optionally enqueues monitors
  (`runFirst`), polls each until its result is fresh (timestamp at or after the task's start) or
  `timeout` elapses, applies an optional `maxAge` staleness check (only when `runFirst` is false), and
  combines every monitor's effective status via `failStrategy` (`FailStrategy`: `FAIL_FAST`,
  `FAIL_IF_ANY`, `FAIL_IF_ALL`, `NONE`) to decide whether the gate passes.
- `io.kestra.plugin.metaplane.MonitorResultTrigger` — polling trigger, fires only when a monitor's
  status changes since the last poll (dedup via namespace KV store).

### Project Structure

```
plugin-metaplane/
├── src/main/java/io/kestra/plugin/metaplane/
├── src/test/java/io/kestra/plugin/metaplane/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://docs.metaplane.dev/reference
