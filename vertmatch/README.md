# VertMatch integration

OpenVitals can send an activity to [VertMatch](https://vertmatch.run) and show the runner's VertMatch
training summary, through the [VertMatch partner API](https://vertmatch.run/developers)
([machine-readable spec](https://vertmatch.run/partner-api.llm.txt), document version 2 of 2026-11-11: any
activity type, classified from the file's sport). The API is in early access and may change before v1.0.

**It is off by default.** OpenVitals ships without the `INTERNET` permission ([AGENTS.md](../AGENTS.md),
"No `INTERNET` permission") and this integration needs a socket. So the default build contains no VertMatch
code, no VertMatch strings and no `INTERNET`. It is compiled in only when the build asks for it:

```
./gradlew :app:assembleRelease -Popenvitals.vertmatch=true \
    -Popenvitals.vertmatch.clientId=<client id from VertMatch> \
    -Popenvitals.vertmatch.refreshTokens=true   # only once VertMatch agreed refresh tokens
```

The client id can also come from the environment variable `OPENVITALS_VERTMATCH_CLIENT_ID`. Without a
client id the integration builds but shows "not set up", and nothing can connect. Whether the maintainer
ships that build, and through which channel, is their decision. Shipping it changes what
`docs/app/privacy.md`, `docs/app/permissions.md`, the FAQ and the store listings say. `DormantBoundaryTest`
holds the line: it fails if the default build could reach the integration or lose the `INTERNET` removal.

## Where the code is

| Part | Where | In the default build |
| --- | --- | --- |
| Partner API client: PKCE sign-in, token exchange and refresh, upload, delete, summary, error handling | `vertmatch/` (this module, plain Kotlin/JVM) | No |
| App side: encrypted token store, connection logic, VertMatch screen, sign-in redirect, Hilt bindings, strings, the manifest that asks for `INTERNET` | `app/src/vertmatch/` | No |
| Three extension points, each an empty Hilt set by default | `features/settings/SettingsExtension`, `features/activity/ActivityDetailExtension`, `data/repository/contract/ActivityDeletionListener`, declared in `di/ExtensionsModule` | Yes, empty |

```
./gradlew :vertmatch:test     # the client and the boundary test
```

## What the runner sees (opt-in build)

- **Settings**: a VertMatch card after the settings categories, above Support, opens the VertMatch screen. That screen says what
  is sent and received, connects through the browser (Auth0 at `auth.vertmatch.run`, PKCE, no client secret),
  disconnects, and shows the training summary: goal race, and for premium runners the last seven days' load,
  fitness and fatigue.
- **An activity's detail screen**: a VertMatch card under the export card with **Send to VertMatch**. Nothing is
  sent automatically, which keeps partner requirement r3 ("uploads only what the runner recorded or asked to
  send"). Sending again is safe: VertMatch answers `unchanged`.
- **Deleting an activity** that was sent deletes VertMatch's copy too (requirement r6). A delete that cannot
  go through right away is kept and retried on the next call.
- **Disconnect** deletes the tokens, the Keystore key and the list of sent activities from the phone.

## How it behaves

- **What is sent.** The activity as a FIT file, from the same encoder as the FIT export, with the route when
  there is one. No `virtual_*` developer fields: those are for rides in a virtual world, and
  `virtual_platform` would make VertMatch treat the GPS track as a course. VertMatch takes the kind of
  activity from the file's sport: a run arrives as a run, a ride as cross-training.
- **External id.** `openvitals-<Health Connect session id>`: stable for the same session, visible ASCII
  only, as the server requires.
- **Tokens.** Encrypted with an AES-GCM key in the Android Keystore. A backup restored on another phone
  cannot decrypt them, so it reads as not connected.
- **Errors** follow the document's handling table (`VertMatchError.action`):
  - An expired token is refreshed once and the call retried.
  - A refused token or scope means signing in again.
  - `ACCOUNT_DELETED` drops the connection.
  - `client_not_allowed` and `app_suspended` stop every call until the runner connects again.
  - Transient failures say "try later".
- **Logging.** Nothing logs: no token, file or id.
- **Duplicates.** A runner who also syncs Health Connect into VertMatch already has the activity there. The
  upload then answers `duplicate` and stores nothing.

## Known gaps

- **Translations.** The integration's strings are English only (`app/src/vertmatch/res/values`), outside the
  translated set. They go to Weblate if and when the build ships.
- **CI.** CI does not build the opt-in variant yet. A step running
  `:app:compileCiKotlin -Popenvitals.vertmatch=true` would keep it compiling.
- **Retries.** No background retry: a pending delete waits for the next VertMatch call (opening the screen,
  sending, reloading the summary).
- **Disconnect.** Disconnecting is local. The runner's grant stays with VertMatch until they remove it there,
  but without the tokens the app can no longer use it.
