# How to use the Metaplane plugin

Metaplane is a data-observability tool that runs SQL-based anomaly-detection monitors against your
warehouse tables. This plugin lets a Kestra flow trigger a monitor run, read its latest result, list
the monitors in a workspace, and react when a monitor's status changes — see the
[Metaplane API reference](https://docs.metaplane.dev/reference) for the underlying HTTP API.

## Authentication

Every task and the trigger require an `apiToken`: a Bearer token generated at
[app.metaplane.dev/account/manage-tokens](https://app.metaplane.dev/account/manage-tokens). Store it
as a Kestra [secret](https://kestra.io/docs/concepts/secret) and reference it with
`{{ secret('METAPLANE_API_TOKEN') }}`, or set it once via
[plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) so it doesn't need to be
repeated on every task.

`baseUrl` is optional and defaults to `https://dev.api.metaplane.dev`, the API host documented at
[docs.metaplane.dev/reference](https://docs.metaplane.dev/reference) (not `app.metaplane.dev`, which is
the web app). Override it if Metaplane changes or adds hosts for your account.

## Tasks

- **`Run`** — enqueues one or more monitors (`monitorIds`) to run immediately via
  `POST /v1/monitors/run`. The API only confirms the run was enqueued; it does not return a run ID or
  wait for completion. `monitorIds` must contain at least one ID or the task fails fast before calling
  the API.
- **`Get`** — reads the latest status of a single monitor (`monitorId`) via
  `GET /v2/monitors/status/{monitorId}`. This is a pure read: it never fails or halts the flow because
  of an anomaly, it only reports `status` (`OK`, `ANOMALY`, `ERROR`, or `UNKNOWN` for any value not
  recognized yet). Gate a pipeline on the result with a downstream
  `io.kestra.plugin.core.execution.Fail` task conditioned on `outputs.<taskId>.status`. Fails with a
  clear error if the monitor has never been run (the API returns HTTP 404 in that case). Since `Run`
  only enqueues a monitor and does not wait for completion, insert a wait (e.g.
  `io.kestra.plugin.core.flow.Pause`) between `Run` and `Get` in the same flow, or read the status once
  you know a run has finished elsewhere (e.g. it runs on its own schedule) — see the
  `MonitorResultTrigger` section below to react to a run as soon as it completes instead.
- **`List`** — lists the monitors defined for a given Metaplane connection (`connectionId`) via
  `GET /v1/monitors/connection/{connectionId}`, optionally filtered with `includeDisabled` and
  `fetchGroups`, with the standard `fetchType` semantics (`FETCH`, `FETCH_ONE`, `STORE`, `NONE`). The
  exact response shape isn't confirmed by Metaplane's official docs, so it accepts either a bare JSON
  array or an object wrapping the array under a `monitors` key, and fails with a clear error on any
  other shape.

## Triggers

- **`MonitorResultTrigger`** — polls a single monitor's status at `interval` (default `PT5M`) and
  fires an execution only when the status changes from the last-seen value, so an unchanged status
  never re-fires the trigger every interval. The first evaluation only establishes the baseline status
  and does not fire. Outputs `monitorId`, `status`, and `checkedAt`. Like `Get`, it fails with a clear
  error if the monitor has never been run.
