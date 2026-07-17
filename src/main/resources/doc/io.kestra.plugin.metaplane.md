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

`baseUrl` is optional and defaults to `https://app.metaplane.dev/api`. Metaplane's official docs do not
publish a confirmed, stable base URL, so override it if Metaplane documents or changes the production
endpoint for your account.

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
  clear error if the monitor has never been run (the API returns HTTP 404 in that case).
- **`List`** — lists the monitors in the workspace via `GET /v1/monitors`, with the standard
  `fetchType` semantics (`FETCH`, `FETCH_ONE`, `STORE`, `NONE`). The exact response shape isn't
  confirmed by Metaplane's official docs, so it accepts either a bare JSON array or an object wrapping
  the array under a `monitors` key, and fails with a clear error on any other shape.

A typical pattern is `Run` followed by `Get`, then a `Fail` task gating on the status — see the
example on the `Run` task.

## Triggers

- **`MonitorResultTrigger`** — polls a single monitor's status at `interval` (default `PT5M`) and
  fires an execution only when the status changes from the last-seen value, so an unchanged status
  never re-fires the trigger every interval. The first evaluation only establishes the baseline status
  and does not fire. Outputs `monitorId`, `status`, and `checkedAt`. Like `Get`, it fails with a clear
  error if the monitor has never been run.
