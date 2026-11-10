# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is pre-1.0
and developed on `main`; until the first tagged release, "Unreleased" is the
live state.

## [0.19.1]

### Fixed — the bundled sandbox never reached the device

The app ships a complete Alpine image and the agent's `node_modules` inside the
APK so a first run needs no network. Neither was reachable.

AGP's `mergeAssets` decompresses any asset whose name ends in `.gz` and drops
that suffix. `alpine-baked-aarch64.tar.gz` therefore arrived as a plain tar
called `alpine-baked-aarch64.tar`, so `assets.open("…tar.gz")` threw, the code
silently fell back to downloading a rootfs, and `wrapper-modules` failed the
same way — logged as a warning, leaving the sandbox reporting Ready while the
agent had no dependencies and could not answer a single prompt.

Two obvious fixes are wrong, and both were measured rather than assumed:

- `androidResources { noCompress += "gz" }` does not help. It was added, built,
  and the APK still contained `.tar`: the decompression happens in
  `mergeAssets`, which runs long before any packaging option exists.
- Renaming `.tar` back to `.tar.gz` would not help either — by then the bytes
  are a plain tar, not gzip.

Assets are now baked as `.tgz`, which `mergeAssets` leaves untouched. That was
established by probing six candidate suffixes, of which only `.gz` was
rewritten. The runtime also stopped trusting names: it sniffs the gzip magic,
extracts plain or gzipped tars alike, and tries `.tgz`, `.tar.gz` and `.tar` so
a build made before this change still provisions instead of quietly
downloading.

Failure is no longer silent. A release with a missing or malformed image fails
the build (`verifyBundledSandbox`); the built APK is opened and checked before
it is published (`scripts/verify-apk-rootfs.sh`); on ARM64 a missing bundled
image raises an error naming `bake-rootfs.sh` instead of falling back to the
network; and the wrapper dependency tree is fatal if absent, for the same
reason — a sandbox that installs and then cannot run a command is worse than
one that refuses to install.

Side effect: release builds are roughly twice as fast, because AGP no longer
inflates 280 MB of tarballs into the intermediates only to deflate them again.

## [0.19.0]

### Added — conversations survive the process

The transcript lived only in memory, so a process kill — routine on this device,
not exceptional — lost the whole conversation. It is now written to an
app-private JSON file atomically (temp file then rename), carrying a version;
an unreadable file is moved aside as `.bak` rather than deleted, since it is the
only copy. Writes are throttled to one per two seconds with forced writes on
`agent_end`, on backgrounding and on any conversation switch. Thirty
conversations are kept, newest first; a conversation with no user message is not
saved at all.

A tool that was running when the process died can never finish, so `RUNNING`
becomes `INTERRUPTED` on load — a spinner that turns forever is indistinguishable
from a hang. Tool output is truncated to the same 400 lines the UI shows before
it is written, with a per-session size cap that drops the oldest tool output
first.

### Added — history in the drawer

Saved conversations appear in the drawer with a title taken from their first
user message and a relative time. Long-press deletes, after a confirmation.

The limit is stated rather than papered over: the wrapper holds one Agent for
the life of a process and its protocol accepts no prior messages, so a reopened
conversation cannot be continued. History therefore opens read-only — the
composer is disabled and a banner says the context does not carry over, with the
one action that makes sense, starting a new chat from it.

### Changed — leaving a live conversation asks first

Opening a saved conversation or starting a new chat while a session is running
saves the current one before switching, after asking. Cancelling changes
nothing.

### Security — conversations stay on this device

A conversation can contain a key pasted into a prompt or command output, so
`chat-history.json` and its siblings are excluded from cloud backup and
device-to-device transfer.

Item ids became UUIDs: a counter would have to be reseeded past whatever a
restored file contains, and a collision there is a duplicate LazyColumn key —
a crash, not a cosmetic bug.

Strings 186 -> 209, identical across all four locales.

## [0.18.0]

### Added — replies render as Markdown

Replies arrived as raw Markdown, so an answer with headings and a code block
read like source. The reader is written here rather than pulled in: Markwon is a
View-system dependency needing an AndroidView bridge with its own font and
colour model, which would double the theming work for a theme that now switches
at runtime. Coverage is the slice the agent actually uses — headings, bold,
italic, inline code, fenced code, ordered and unordered lists, quotes, rules,
links.

Two properties mattered more than coverage. Streaming tolerance: text arrives a
few characters at a time, so the parser is permanently fed input that ends
mid-token, and an unterminated marker degrades to its literal characters rather
than throwing or swallowing them. Theme independence: parsing yields spans and
ranges rather than colours, so the result is cached per message and painted by
whatever theme is active.

Nineteen unit tests cover it, most of them about the unclosed cases, including a
sweep that feeds every prefix of a document and asserts nothing disappears.

Code blocks scroll sideways instead of wrapping — wrapping destroys the
indentation that carries the meaning — and carry a copy button. No syntax
highlighting. Links open in the browser on tap, and only `http`/`https` become
links at all.

### Added — tool calls fold to one line

A spinner while running, then a tick or a cross. Expanded they show the command
(the `command` argument, else the formatted arguments) and the output, stopping
at 400 lines and saying so. The unfolded set is keyed by item id and hoisted
above the settings switch, so a round trip does not fold everything back up.

### Changed — reading aloud strips the markup

On both paths. A code block is announced with one sentence instead of being
spelled out character by character.

Strings 180 -> 186, identical across all four locales.

## [0.17.0]

### Added — every row has an identity

`TranscriptLine(role, text)` could only say "somebody said something". A tool
call is not a message: it starts, finishes, can fail, and carries a command and
its output. It is now a sealed `ChatItem`: `MessageItem(role, text)` and
`ToolItem(toolCallId, name, status, args, result)`, each with a stable id that is
assigned once and never reused — the list keys on it, and renumbering would make
LazyColumn treat a row as a different one, losing its scroll anchor and
replaying its entry animation.

`tool_execution_end` is now handled: start appends a running row, end matches by
`toolCallId` and records success or failure plus the result. An end without a
start still produces a row rather than vanishing. `tool_execution_update` stays
ignored — it fires per chunk, and rebuilding the list that often would make the
transcript scroll like a terminal.

### Fixed — auto-scroll during streaming

It watched `transcript.size`, which never changes while a reply streams: one row
keeps growing while the text runs off the bottom of the screen. It now watches
the last row's content, follows only while the view is parked at the bottom, and
stops when the user scrolls away — with a button to return.

The follow flag is hoisted next to the list state: left inside the pane it would
be re-created as `true` on every return from settings and yank a reader who had
deliberately scrolled up back to the bottom.

Auto-speak now dedupes by message id rather than text: asking the same question
twice produces two replies that may read identically, and both should be spoken.

Strings 179 -> 180, identical across all four locales.

## [0.16.1]

### Changed — every user-facing string is named, not spelled

AppViewModel has no Context, so it hardcoded Chinese and English literals. The
language setting therefore changed the interface but not the confirmations, the
failures, or the transcript's own system lines. It now emits
`UiMessage(id, args, toTranscript)` on a buffered Channel, and everything is
resolved inside PiFillingApp under the localized context the language setting
installs. System lines are appended after resolution, so the transcript keeps
finished sentences — switching language later must not retranslate a
conversation that happened.

Fixed on the way through, because they were the same bug: the auto-install
toggle spelled its own confirmation in Chinese, and MainActivity resolved the
microphone messages with the base context.

### Changed — switching models asks first

A live wrapper is bound to the model it started with, so switching ends the
session — and that used to silently throw the conversation away. The user is now
asked; cancelling keeps both the model and the transcript. With no session
running the switch is immediate. Both entry points, the model sheet and the
composer's model chip, go through the same request.

### Added — replies are read aloud when asked

Auto-speak was a flag the agent could read and nobody acted on. On `agent_end`
the app now speaks the finished reply when both switches are on, and stands down
for the rest of a run when the agent spoke by itself, so nothing is said twice.

Strings 159 -> 179, identical across all four locales.

## [0.16.0]

### Changed — settings regrouped into six sections

The list had grown by accretion: a row titled "System" because it reused the
wrong string, "Model" and "Saved models" both opening the same sheet, three rows
sharing one icon, a status pill that repeated a row, and a footer reading
"N models · Pi-Filling" that told nobody anything.

Regrouped into what people scan for — model & service, GitHub, runtime,
appearance & language, voice, about — each row carrying one line that says what
it does, and every icon distinct. The status card is now four tap targets
(model / key / sandbox / GitHub) that scroll to the row that sets them.

The settings top bar keeps only the back arrow; the trailing Menu button that
also closed settings was a second, misleading way to do the same thing.

### Added — theme, and the controls the storage already had

Theme: follow system, light, or dark, persisted. Voice gained auto-speak, rate
and pitch — the last three greyed out while speech itself is off, since they
describe how replies are spoken.

Theming needed a plumbing fix, not just a control: Glass.kt called
`isSystemInDarkTheme()` in three places, so choosing "Dark" on a light phone
still drew a light backdrop, light panes and light sheets. PiFillingTheme now
publishes `LocalPiFillingDark` and the glass surfaces read that; edge-to-edge is
re-applied when the resolved darkness changes so the system bar icons stay
legible in all three settings.

### Fixed — the draft and the scroll position survive settings

The chat pane and the settings screen share one AnimatedContent, so opening
settings destroys the chat composition. Everything remembered down there went
with it: half-typed messages vanished and the transcript jumped back to the top.
The draft now lives in the ViewModel and the list state is hoisted above the
switch.

Strings 134 -> 159, identical across all four locales.

## [0.15.0]

Everything since the installed 0.13.3: the 0.14.0 UI pass, plus this release's
chat-as-chat-app work and the capability settings.

### Changed — replies read as replies (0.14.0)

- Replies are no longer wrapped in a bubble; the model is the title of the
  screen and the empty state is a grid rather than a list of suggestions.

### Added — the chat behaves like a chat app

- Long-press any message for a menu: copy it, copy the whole conversation,
  read a reply aloud, regenerate the last exchange, or pull the last message
  back into the composer for editing. "Regenerate" and "edit" retract the
  tail, so re-asking never duplicates the transcript.
- The composer gains a microphone button that opens the system speech
  recognizer — say it in Mandarin and it becomes text. The app records
  nothing itself; the recognizer asks for the microphone permission, and the
  recognized text appends to whatever is already typed. The recognizer
  language follows the app's language setting (zh-CN / zh-TW / en-US /
  system).
- Sending while a reply is still streaming now stops that run and queues the
  new message instead of colliding with "busy".

### Added — settings reorganized, with capability switches

- Settings are regrouped the way people actually scan them: **模型与服务**,
  **密钥与仓库**, **能力**, **沙箱与工具**, **通用** — with right-side
  status text (已保存 / 已开启 / 就绪) instead of nested sheets.
- New **能力** group holds the switches ordinary users care about: 语音朗读,
  语音输入, and **手机操控** — a per-capability sheet (通知 / 打开应用或链接 /
  闹钟 / 计时器 / 日历日程). Turning a capability off removes its tools from
  the next session: the allow-list is built per session, so a disabled
  capability is not merely refused, it is not offered.
- GitHub token got its own sheet and row under 密钥与仓库 (it used to share
  the repo-path sheet).
- An **关于** sheet shows the version; the footer now includes it too.

### Changed — read-aloud is an explicit user action

- The message menu's 朗读 speaks on tap and does not require the voice master
  switch: the switch gates what the *agent* may start by itself, while a tap
  on "read this to me" is already the user's decision. The shared speak path
  is unchanged for the agent's own `voice_speak` tool.

### Fixed — messaging while a session is still connecting

- Sending during Connecting/Provisioning previously started a second wrapper
  on top of the first; the message is now queued and flushed when the session
  reaches Ready.

### Fixed — custom provider transient failures now recover

- A `422 upstream_request_rejected` whose body says "Service is temporarily
  unable to complete the request. Please retry later." is a capacity blip, not a
  bad payload. pi-ai's built-in retry only covers 408/409/429/5xx and the app
  ran with `maxRetries: 0`, so the blip surfaced as a hard failure. The custom
  provider now routes requests through a fetch wrapper that translates a
  *transient* 422 into a 503 with a `retry-after` hint and hands the built-in
  backoff layer 4 retries. Non-transient 4xx (bad key, rejected payload) still
  fail fast with the true cause.
- The chat now says "service is busy, wait a moment and resend" when that
  marker appears, instead of a bare 422.

### Fixed — sandbox tool install ("1 error; 167 MiB in 120 packages")

- `/etc/apk/repositories` now lists every Alpine mirror (main + community for
  each), so apk fail-overs when one CDN file 404s or times out — the single
  mirror that used to be written was the whole install's single point of
  failure. The refresh runs on every explicit install/repair, not just
  first-time provisioning.
- `apk add` now retries once after an index refresh when it fails transiently,
  and on failure reports the actual `ERROR:` lines (which package) instead of a
  trimmed stderr tail.
- Rootfs downloads lead with the Tsinghua mirror on zh-locale devices, and the
  sandbox's resolver uses AliDNS/Tencent DNS before Google (8.8.8.8 is
  unreliable on mainland carriers).

### Added — agent can operate the phone

- Five new agent tools over the host-capability channel: `phone_notify`,
  `phone_open` (url/app), `phone_alarm`, `phone_timer` (chronometer
  notification + hard alarm), `phone_calendar`. All go through stock Intents /
  notification APIs, so the OS still owns the final confirm; no new
  permissions beyond the already-declared `POST_NOTIFICATIONS`.

### Added — saved model presets + chat-box model switcher

- Every picked model is remembered per provider (up to 6, most recent first)
  in `ui/ModelPresets`. The composer gets a swap button with a dropdown of
  saved models — switching there ends the live session so the next message
  runs on the new model. The model sheet gains a starred "saved" section with
  long-press to remove.
- Settings gains a "常用模型" row showing what is saved.

### Changed — UI pass: motion + a real settings layout

- Settings and chat now cross-fade (AnimatedContent) instead of hard-cutting;
  settings groups stagger in; new messages ease in; a three-dot thinking row
  appears while a request is in flight.
- The model picker, saved section, and quick switcher are the "switch between
  several models without leaving the chat box" flow.

## [0.12.0]

### Fixed — custom provider rejected every request (400)

- `custom` claimed `reasoning: true`, so pi-ai sent a `developer` role,
  `reasoning_effort`, `max_completion_tokens`, `store` and strict tool schemas.
  `detectCompat()` cannot recognise an arbitrary relay, so it assumes the newest
  OpenAI surface; a plain OpenAI-compatible gateway answers
  `400 upstream_request_rejected` on the first prompt. The provider now declares
  a conservative compat (system role, `max_tokens`, no `store`, no strict mode)
  and sends reasoning fields only for `--reasoning` / the new "推理模型" switch.
- The wrapper logs the wire shape it chose (`baseUrl`, api, reasoning,
  max-tokens field) so a rejected request is diagnosable from the error panel.

### Changed — UI: frosted glass

- New `ui/Glass.kt` gives the app one surface language: an aurora backdrop with
  every panel on top rendered as a translucent pane, hairline highlight and soft
  shadow. Chat bubbles, tool chips, quick prompts, settings groups, the identity
  card, the drawer and the bottom sheets all use it.
- The composer is a single floating rounded pane with a borderless field,
  replacing a boxed text field above a divider.
- No new dependency: a real backdrop blur needs API 31 or a library, so the
  effect is translucent gradients plus a border (works on minSdk 26).

### Added — visible install progress

- `ProotExecutor.execute` can stream guest output; the sandbox sheet shows it
  live with a spinner while `apk`/`npm` install, instead of a disabled button.

### Fixed — CI

- The hermetic wrapper suite seeded `ANTHROPIC_API_KEY` while booting the
  default provider, so every boot test failed with an empty stderr.

## [Unreleased]


### Production-readiness pass

A multi-dimension audit (correctness, security, protocol, testing, CI/build,
dependencies, docs) drove the following.

#### Changed — dependencies (blocker)

- **Migrated the agent stack off the deprecated `@mariozechner/*` npm namespace
  to its maintained successor `@earendil-works/*`** (`pi-agent-core`, `pi-ai`,
  `pi-coding-agent`), pinned to exact `0.78.0`. The old namespace was frozen at
  `0.73.1` and formally deprecated. Verified: hermetic wrapper suite green and
  the host-Alpine spike's Q1/Q2/Q3 pass on the new packages. Applies to both
  `node-wrapper/` and `spike-host-alpine/`. (The "0 vulnerabilities" claim
  originally recorded here no longer holds — see the public-readiness pass
  below and `SECURITY.md`.)
- Switched dependency ranges from caret (`^0.73.0`) to exact pins; added
  `node-wrapper/.npmrc` (`engine-strict=true`) and a startup Node-version guard.

#### Added — wrapper hardening (`node-wrapper/src/wrapper.mjs`)

- Flush stdout before exit so a backpressured pipe can't truncate the final
  `agent_end`; honor stdout backpressure in the event listener.
- Last-resort `uncaughtException` / `unhandledRejection` handlers that emit a
  `wrapper_error` and exit **4** (the previously-unreachable documented code).
- Robust request parsing: a bare `null`/array no longer crashes dispatch; a valid
  id with a non-string method gets a `bad_request` response instead of hanging.
- Caught `parseArgs` and `--system-prompt` read failures (clean exit 1, not an
  uncaught stack trace); defensive JSON serialization and an EPIPE guard.
- Surface a run that ends in a provider/auth error as a `wrapper_error`.

#### Security

- The API key is captured into a closure and then **scrubbed from
  `process.env`** (`ANTHROPIC_API_KEY`/`ANTHROPIC_OAUTH_TOKEN`) so it can't leak
  to the agent's `bash`-tool children or pi-ai's env auth fallback.
- `memory.md` is now folded into the system prompt as **untrusted reference
  data** with closing-delimiter-breakout neutralization (it is git-synced across
  devices — prompt-injection surface).
- `SECURITY.md` documents the current key-handling posture and that proot is a
  compatibility layer, not a security sandbox.

#### Added — tests & CI

- Shared promise-based test harness (`test/harness.mjs`) replacing busy-poll RPC
  loops; new `test/hardening.mjs` (exit-code contract + malformed-input
  robustness); event-ordering and stdin-close-mid-run coverage in integration.
  Hermetic suite grew from 8 to **23** tests.
- GitHub Actions CI (`.github/workflows/ci.yml`): lint + format + hermetic tests
  on Node 22, `npm audit`, the Alpine/musl Docker build, and `shellcheck`.
- Dependabot (`.github/dependabot.yml`), ESLint + Prettier configs, root
  `.editorconfig`, and `package.json` metadata (license, repository, version).
- Dockerfiles now use `npm ci --omit=dev --ignore-scripts` for reproducible,
  integrity-checked installs.

#### Added — Android app scaffold (Layer 1, Stage 1.2c)

- New `android/app/` Gradle/Compose project porting Kai's patterns: `DaemonService`
  foreground service, `LinuxSandboxManager` + `ProotExecutor` + `RootfsDownloader`
  (Alpine 3.21.3), AndroidKeyStore-backed `SecureKeyStore`, and a `WrapperClient`
  speaking the Layer 3 stdio JSONL protocol. **Scaffold only — not yet built on a
  device.** See `android/README.md` and `android/KAI_PATTERNS.md`.

### Android: first compile

#### Added — the Android scaffold now builds

- **Committed the Gradle wrapper** (`gradlew`, `gradlew.bat`,
  `gradle-wrapper.jar` at 8.10.2), so a fresh clone builds with no system
  Gradle. It was deliberately omitted before because the scaffold was authored
  in an environment with no JDK or Android SDK; that constraint no longer
  applies. The wrapper was regenerated by Gradle 8.10.2 itself rather than
  copied, so the jar and scripts match the pinned distribution.
- **`./gradlew assembleDebug` succeeds.** Stage 1.2c had been "structurally
  complete but never compiled" since it was written — `compileDebugKotlin`
  passes and the APK packages (16.6 MB, 155 entries). No source changes were
  needed to make it build.
- Confirmed `copyWrapperAssets` genuinely bundles Layer 3: the APK contains
  `assets/wrapper/{src/wrapper.mjs,package.json,package-lock.json}`. That is the
  three-layer wiring verified at build time rather than assumed.
- **Added an `android-build` CI job** running `assembleDebug` on JDK 17 and
  uploading the debug APK as an artifact. `android/` previously had no CI job of
  any kind — the same blind spot that let a breaking dependency bump report
  green for `spike-host-alpine`.

#### Documentation

- `android/README.md` now records the JDK 17 requirement and *why* it is not
  optional: Kotlin 2.0.21 refuses to run on Java 25 and reports only
  `* What went wrong: 25.0.4`. It is neither AGP nor Gradle (Gradle 8.10.2 runs
  on Java 25 fine), and `jvmToolchain(17)` does not help, because it is the
  daemon JVM that must be old enough. Fedora 44 packages no JDK below 25.
- Recorded that `distributionSha256Sum` is still unpinned in
  `gradle-wrapper.properties`, as a supply-chain follow-up.

### Stage 1.6: the host-capability channel, and speech on it

The agent runs inside proot with no route to Android, so anything phone-native
had to be *asked for* rather than executed. This adds the asking, and the first
thing worth asking for.

#### Added — the channel

- **A reverse RPC over the existing JSONL framing**
  (`node-wrapper/src/host-channel.mjs`): `host_request` out, `host_response`
  back, correlated by id, bounded by a per-request timeout. Built under three
  constraints so it stays promotable to a pi-level mechanism rather than
  remaining ours: capability names are namespaced and host-neutral (`tts.speak`,
  never `androidSpeak`), no host specifics appear on the wire, and the transport
  is injected rather than imported. A test asserts no platform string reaches
  the wire.
- **The Android side** (`wrapper/HostCapability.kt`): an interface, a refusal
  type carrying `unsupported_capability` / `not_permitted`, and an allow-listed
  registry that never throws — every outcome becomes a `host_response`, because
  a capability that blows up must not take the reader thread with it.
  Capabilities are registered by Layer 1 and addressed by name, so the agent may
  *ask* for one but can never *introduce* one. `WrapperClient` dispatches them
  off the stdout reader so a slow capability cannot stall streaming.

#### Added — speech

- `voice_speak` and `voice_config`, ported from
  `TheAmericanMaker/pi-termux-android-voice` (MIT, attributed in-file). The
  surface is the original's; the transport is not — Termux shelled out to
  `termux-tts-speak`, which has no equivalent inside proot. The substitution is
  an upgrade: a real `TextToSpeech.stop()`, real chunking driven by
  `UtteranceProgressListener` rather than guessed timing, and no Termux:API to
  install. Slash commands were dropped as TUI affordances; auto-speak state
  moved into the host, where it survives sandbox rebuilds.
- **Speech is off until a person turns it on.** The agent can read the setting
  and toggle auto-speak, but cannot enable speech for itself — a model should
  not be able to start talking out of someone's pocket. Tools degrade rather
  than throw when refused, and are told plainly when retrying is pointless.

#### Fixed — a wrapper change never reached a provisioned sandbox

`deployWrapper` ran only inside `setup()`, which short-circuits on the
completion marker, so any wrapper change after first provisioning was invisible
on device — the phone silently ran the wrapper it was first given. The marker
now carries a content digest of the wrapper asset tree and `ensureCurrent()`
redeploys when it differs, so this cannot depend on remembering to bump a
version. Found on hardware while testing the channel; see
`android/VERIFICATION.md`.

#### Fixed — speech could not be switched off while the agent spoke

The voice switch was written into the idle branch of the session screen, so it
existed only before a session started. Moved to a row present in every state;
turning it off stops whatever is mid-utterance.

### Direction: mobile-native feature design

- Recorded the design for a **host-capability channel** — a reverse RPC letting
  Layer 3 ask Layer 1 for Android-native work (`ARCHITECTURE.md`). The protocol
  is currently one-directional, which is why the agent cannot speak, notify, or
  reach the share sheet: nothing carries the request outward. Allow-listed by
  Layer 1, refusable, and opt-in for anything with a real-world side effect.
- Established that **voice input needs no code**: any Android dictation keyboard
  types into the prompt box as normal text. This was already true and unnoticed.
  Offline recognition via Vosk is recorded as the eventual direction, not v1.
- Text to speech is the first planned capability, porting the extension from
  `pi-termux-android-voice` onto the channel. That project's own notes name "a
  small Android companion app that calls `TextToSpeech.stop()`" as the fix for
  problems it could not solve from Termux — Layer 1 is that app, so the port
  gains a real stop and real chunking.
- Added **Stage 1.6** to the roadmap around one principle: on a phone the human
  is a director and reviewer, not a typist. Suggestion chips, prompt templates,
  one-tap skills, diff review with per-hunk accept, session resume, share-sheet
  ingestion, OCR.
- No implementation yet, but both open decisions are now settled. The voice
  extension is **vendored here**: its transport is being replaced outright and it
  will grow phone-specific behaviour Termux has no use for, so sharing it
  upstream would be a fork in disguise. `pi-termux-android-voice` stays canonical
  for Termux; this is a sibling sharing its design and command vocabulary, with
  attribution.
- The capability channel is **ours first, shaped so it could become pi's**. pi's
  `ExtensionAPI` has lifecycle hooks, commands and tools but no host surface —
  which is exactly why the Termux version shells out to a Termux:API binary, and
  why that cannot work from inside proot. The gap is general to any pi embedded
  in a native app, but a proposal is stronger after shipping than before. Three
  constraints keep the door open at no cost: namespaced host-neutral capability
  names, no Android specifics on the wire, and the transport isolated behind one
  module.

### bash in the sandbox

- **Fixed: the agent is promised a bash tool in a sandbox with no bash.**
  Provisioning installed `nodejs npm git`, and Alpine's `/bin/sh` is busybox, so
  bash-only syntax failed with `sh: bash: not found` and
  `syntax error: bad substitution`. Seen across two models, so it is an
  environment defect, not a model quirk. `bash` is now part of the package set,
  backfilled into existing sandboxes via `SETUP_VERSION` 3.
- Effect, measured on device with an identical task and repo state per run:
  `minimax-m3` went from 20 tool calls / 15 errors to 6 / 0. No post-fix run of
  any model produced a bash-related error. `gpt-oss:120b` remains noisy run to
  run for unrelated reasons.
- Documented a credential-exposure path in `SECURITY.md`: the app forwards
  wrapper stderr to logcat, and wrapper diagnostics echo CLI arguments, so a
  credential mistyped into a field that becomes an argument can reach the device
  log.

### Git identity in the sandbox

- **Fixed: the agent could not commit.** Alpine ships no git identity, so
  `git commit` failed with `Author identity unknown`. On hardware the agent
  responded by inventing `Assistant Bot <assistant@example.com>` and writing it
  into the *operator's repository* local config — the commit landed, so this
  looked like success anywhere except `git log --format=%an`. Provisioning now
  configures `Pi-Filling Agent <agent@pi-filling.local>` globally inside the
  sandbox, plus `safe.directory=*` (proot maps the repo owner to root, which
  git's ownership check otherwise rejects).
- **Fixed: new provisioning steps never reached existing sandboxes.**
  `SandboxState` starts `Ready` from the marker file alone, so `setup()` is not
  called again and any step added later would silently never run. The marker now
  carries a setup version and `ensureCurrent()` backfills from `startSession`,
  avoiding a ~350 MB re-provision.
- Verified on device: prompt typed in the app produced a real commit, authored by
  the configured identity, with no local config written to the repo — and the
  agent created and committed `memory.md` unprompted, exercising that feature on
  hardware for the first time.

### Provider selection in the Android app

- `--provider` is now reachable from Layer 1. Added `AgentProvider`, mirroring the
  wrapper's `PROVIDERS` table, and threaded it through `SecureKeyStore`,
  `LinuxSandboxManager.startWrapper`, `AgentController.startSession`,
  `AppViewModel` and the key-entry screen.
- **Credentials are stored per provider.** `SecureKeyStore` keys on
  `<provider>_api_key`; Anthropic keeps its historical `anthropic_api_key` name, so
  an existing install does not lose its key. Switching provider re-reads whether
  *that* provider has a credential rather than assuming one covers both.
- **Fixed: the agent's answer never appeared in the app.** The transcript builder
  looked for the streamed chunk at `assistantMessageEvent.text` or `data.delta`;
  the wrapper emits `assistantMessageEvent.delta` with the kind in
  `assistantMessageEvent.type`. Now reads the right field and gates on
  `text_delta`, so `thinking_delta` reasoning is not spliced into the reply.
- Verified end to end on a Galaxy Z Fold 5: prompt typed in the app, agent called
  `bash` and `read` inside proot/Alpine, answer rendered in the transcript. See
  `android/VERIFICATION.md`.

### Provider support

#### Added — `--provider` on the node wrapper

- The wrapper accepted only Anthropic, so verifying the live agent loop meant
  spending against Anthropic. It now takes `--provider anthropic|ollama`, with
  `anthropic` as the default and the only provider Layer 1 wires up. V1_SPEC
  records this as a development affordance, **not a v1 shipping target**.
- No forked code path: a pi-ai `Model` is plain data and `streamSimple`
  dispatches on `model.api`, so provider selection is a small data table.
  `ollama` describes an `openai-completions` model against
  `https://ollama.com/v1`; `anthropic` still resolves through pi-ai's builtin
  catalog and still exits 3 on an unknown model id.
- Each provider reads its own key variable — an `ANTHROPIC_API_KEY` does not
  satisfy `--provider ollama`. `wrapper_ready` now reports `provider` alongside
  `model` (an additive field; protocol stays v1).
- **Verified end-to-end against a live provider**, which had been the standing
  gap since the 0.84.3 migration: the agent read a file via the `read` tool and
  answered correctly in 715 tokens, exit 0. This exercises the real streaming
  path and the `getApiKey` → `options.apiKey` handoff through the new
  `streamFn`. Note it exercises `openai-completions`; the `anthropic-messages`
  path still has no live-call verification in CI.

#### Security

- The wrapper now scrubs **every** known provider key from `process.env` after
  capture, not just the one in use. The `bash` tool should never see a
  credential for a provider the run isn't talking to.
- The test harness scrubs all provider keys from the inherited environment.
  Without this, a developer machine with a real key exported could have the
  suite make a real, billed API call — it previously scrubbed only
  `ANTHROPIC_API_KEY`.

#### Tests

- 23 -> 25. Added: unknown `--provider` exits 1 and names the valid ones;
  `--provider ollama` rejects an `ANTHROPIC_API_KEY` and demands
  `OLLAMA_API_KEY`. Smoke now asserts `wrapper_ready.provider`.

### Public-readiness pass

Consolidating the outstanding branches and closing the gaps that would have
shipped with a public repo.

#### Changed — branches

- Opened **`main`** carrying all previously unmerged work: the
  production-readiness branch (CI, lint/format tooling, wrapper hardening,
  Android scaffold) is now contained in it, as is the original spike branch.
- Repointed `README.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `V1_SPEC.md` and
  `spike-host-alpine/RUNBOOK.md` from `claude/spike-pi-agent-android-NugAe` to
  `main`, and dropped the `git checkout` steps that only existed because the
  default branch was not the working branch. `SPIKE_NOTES.md` keeps the branch
  name as a record of where the spike ran.

#### Fixed

- `RootfsDownloader.kt` held two **raw NUL bytes** inside Kotlin char literals
  (the tar entry-type check and the header-string trim). Git classified the file
  as binary, so it produced no reviewable diffs. Replaced with `\u0000` escapes;
  no file in the repo is binary-flagged now.

#### Changed — dependencies

- The three `@earendil-works` packages went `0.78.0` -> `0.78.1` (patch, in
  lockstep) and then to `0.84.3` with the API migration described under
  Security below. Suite green at 23/23 throughout; Q1/Q2/Q3 re-verified.

#### Security

- **Migrated the agent stack to `0.84.3`, taking production advisories from 5
  (4 high) to zero.** The `0.78.x` line could not be patched downstream:
  `pi-coding-agent` shipped an `npm-shrinkwrap.json`, and npm treats a
  dependency's shrinkwrap as authoritative for that subtree, so the vulnerable
  transitives it pinned (`undici`, `ws`, `protobufjs`, `brace-expansion`) were
  unreachable by consumer `overrides` or lockfile edits — both were tried, and
  npm records the override edge while installing the pinned version anyway.
  The upgrade was the only real fix, and it was a breaking API migration:
  - `getModel` → `getBuiltinModel`, from `@earendil-works/pi-ai/providers/all`.
  - `pi-agent-core` is now provider-agnostic and ships no default stream
    function, so `Agent` requires an explicit `streamFn`. Supplied
    `streamSimple` from `pi-ai/compat` — the same bridge `pi-coding-agent`'s own
    SDK installs via `setDefaultStreamFn`, passed explicitly rather than relying
    on that import side effect.
  - Applied to `node-wrapper/` and both key-requiring spike drivers.
  - The key-handling guarantee is unchanged and re-verified: `Agent.getApiKey`
    still exists in `0.84.x` and still hands the value to `streamFn` as
    `options.apiKey`, so the key never transits `process.env`. Spike Q2 asserts
    exactly this and passes.
- Replaced CI's `npm audit --audit-level=high` with
  `node-wrapper/scripts/audit-gate.mjs`. Its allowlist is now **empty** — there
  is nothing to except. It is kept because the failure mode is structural: an
  upstream can pin a vulnerable transitive out of reach again, and the response
  should be a documented per-package exception rather than lowering the
  threshold for everything. Fails on any high or critical outside the allowlist,
  on a critical even when allowlisted, and warns on stale entries.
- Audited all 21 commits of history before publication: no credentials in any
  of the 150 blobs, no sensitive filenames, nothing added-then-deleted.

#### Fixed — CI reported green on broken code

- `spike-host-alpine/` had **no CI coverage at all**, so a dependency bump that
  removed an export its drivers use passed every check while breaking them at
  import time. A Dependabot PR bumping that directory to `0.84.2` did exactly
  that and showed green — `getModel` moves out of `@earendil-works/pi-ai`'s main
  entry point, and `driver-e2e.mjs` and `driver-extras.mjs` both import it. (The
  migration has since been done, so the bump is now taken rather than blocked;
  the check is what made the breakage visible in the first place.)
- Added a `spike-api-contract` CI job. Two of the three drivers need a real API
  key and cannot run in CI, so `scripts/check-api.mjs` asserts their **import
  contract** instead: it reads the named imports each driver takes from an
  `@earendil-works` package and checks the package still exports them. Verified
  it passes at `0.78.0` and fails on the `0.84.2` bump that CI had waved through.
- The job also runs `driver.mjs`, which is hermetic by design (mock `streamFn`,
  no real LLM call), so the Q1/Q2/Q3 spike assertions now run on every push.
- Corrected a stale `@mariozechner/*` reference in `driver.mjs`'s header, left
  over from the namespace migration.

#### Added

- `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1).
- Issue templates for bug reports, reproduction reports and design/scope
  pushback, matching what `CONTRIBUTING.md` names as useful, plus a config that
  routes security reports to private advisories instead of public issues.
- CI and license badges in `README.md`.

### Earlier (pre-audit)

- Stage 1.1: node-wrapper with stdio JSONL RPC + memory.md round-trip.
- Stage 1.2a: vendored Kai's `build-proot.sh`.
- Stage 1.2b: Alpine wrapper Dockerfile + hermetic smoke coverage.
- Stage 0: host-Alpine spike proving the agent stack on musl.
