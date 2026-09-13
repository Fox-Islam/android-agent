# android-agent

An on-device Android agent: you type a task, a model drives the phone.

[Overview, screenshots and demos](https://fox-islam.github.io/android-agent-site/)

No PC, no adb, no root. Privilege comes from an `AccessibilityService`, which is the same
mechanism a screen reader uses - it can read every window and tap anything in it.

It runs on your own API key and talks to whichever OpenAI-compatible endpoint you point it
at. There is no account, no backend of ours, and no telemetry.

## What it can and cannot do

It can open apps, read the screen semantically, tap, swipe, type, press back/home/recents,
and work through a task a step at a time. It can ask you a question when it is stuck, keep a
checklist, and call tools on an MCP server you have configured.

It cannot see banking, payment or DRM screens - those are `FLAG_SECURE` and capture black. It
cannot act while the phone is locked or the screen is off. It cannot tap runtime permission
dialogs. Some apps refuse to run at all while an accessibility service is enabled. It will
tell you when it hits one of these instead of pretending it worked.

Canvas-drawn interfaces - games, some Flutter and WebView screens - expose no useful node
tree. It falls back to reading coordinates off the screenshot, which is materially less
reliable than tapping a node.

## Installing

Sideload only: an accessibility-driven automation app is not Play-distributable. Download
[android-agent.apk](https://github.com/Fox-Islam/android-agent/releases/latest/download/android-agent.apk)
from the latest release, install it, and follow the walkthrough on first launch:

1. **An API key.** OpenRouter by default.
2. **Accessibility.** Settings → Accessibility → android-agent → On. If the switch is greyed
   out, App info → ⋮ → **Allow restricted settings** first - Android blocks this for
   sideloaded apps until you say otherwise.
3. **The floating card** (optional, recommended). While it works you are in some other app;
   the card is how it tells you what it is doing, asks before anything irreversible, and
   gives you a stop button.
4. **Notifications** (optional). The same questions, and the result when a run finishes.

## Starting a task

The task usually occurs to you inside the app it is about, so as well as its own screen the
agent is reachable from the share sheet, a text selection, the assist gesture and a
quick-settings tile. A shared image rides along with the first message.

## Deciding what it is allowed to do

Three layers, checked before every action:

- **Rules** you write, one per line: `deny * in com.*bank*`, `ask type_text matching
  (?i)password|otp`, `allow tap in com.android.settings`. First match wins, and they hold
  whether or not anyone is looking at the phone.
- **Approval mode**: never ask, ask before risky steps (the default), or ask before every
  step. "Risky" means an irreversible-sounding label - send, pay, delete, post, confirm - a
  money or password app in the foreground, or any MCP tool, since those act off-device.
- **Plan mode**, per chat: the agent is handed a looking-only tool list until you approve the
  plan it proposes. It cannot touch anything before then, so this is a property of the app
  instead of a promise in a prompt.

Answering "Always" writes a rule scoped to the app it was granted in.

## Models

Any OpenAI-compatible chat-completions endpoint. Each model must support tool calling and
image input. Set a second, cheap model and the run uses it for the mechanical steps - tapping,
looking, waiting - and goes back to the chosen one to plan, to recover from anything
unexpected, and whenever you say something. In testing that was the difference between $0.07
and $0.016 for the same task.

Plain `http` endpoints work, for a gateway on your own machine. The settings screen warns when
an unencrypted address is not on your own network, because your API key travels with it.

Reasoning has a provider default and an explicit off, which are different things: sending
nothing leaves a reasoning model reasoning.

Replies stream as they arrive, and Stop cancels the HTTP call instead of waiting for the
generation to finish arriving. Transport failures - 429, 5xx, a dropped socket - are retried
with backoff; a bad key or a malformed request fails immediately, because neither is going to
fix itself. Usage and cost come back with each turn and are shown per chat.

You can also type at a run in progress. What you say joins at the next step boundary rather
than queueing behind the whole run, because "no, the other button" is the most common thing
anyone wants to say to an agent that is already moving.

## Chats

A chat is an append-only log of what happened; the transcript and the messages sent to the
model are both projections of it. That is what lets a follow-up resume the real exchange, a
run the system killed be picked back up, and any message be forked from - long-press one and
the chat is copied up to that point into a new one.

## Scripts

Some steps cannot be decided in advance. Tapping a toggle that is already on turns it off;
a list has to be scrolled until the thing appears, however many swipes that takes. So the
agent can write a short Lua script and run it on the phone, deciding as it goes, instead of
paying a round trip to the model between every pair of taps.

```lua
phone.open("Settings")
for _, want in ipairs { "Wi-Fi", "Bluetooth", "NFC" } do
  local node = phone.waitFor({ text = want }, 4000)
  if node then phone.ensure({ text = want }, true) end
end
```

Scripts match on text and ids, never on a saved index - the accessibility tree is
renumbered every time it is read.

A script gets no privilege for being a script. Every action inside it goes back out through
the same path as one the model asked for directly, so your rules and your approval mode
apply to each tap in a loop, and every one of them lands in the chat log. What a script
changes is which actions get attempted, never whether they are allowed.

It is a small language: the string, table and math libraries, and the device functions.
There is no file, network, class-loading or reflection access - those libraries are never
loaded. A script stops after 120 actions or 90 seconds however it loops, and
the budget is not something a script can catch and ignore.

### Keeping one

A script written into a call is gone when the chat scrolls, so doing the same thing next
week means working it out and writing it again. The agent can save one under a name instead,
with a sentence describing what it does and what it expects:

```lua
-- saved as "settings-toggle"
phone.tap(phone.waitFor({ text = args.label }, 8000))
phone.ensure({ id = "switch_widget" }, args.on)
```

Calling it is then a name and its arguments - a dozen tokens instead of thirty lines, and
no second attempt at getting it right. Only the names and descriptions are in the prompt;
the body stays on disk until something calls it, as playbooks do. Reading the parts that
vary out of `args` is what lets one saved script cover a family of cases.

A script that will not compile is refused at save time instead of failing later, in a call
that was relying on it. Saving takes no action on the phone; what a saved script *does* is
gated when it runs, every time it runs, like anything else.

### Running one yourself

Settings → **Saved scripts** lists them, and each has a play button. A script declares which
values it wants and what they are, so this is a form instead of a prompt: a switch for a
yes-or-no, a field for a name. One that never declared any still gets fields, worked out
from the `args` its own source reads.

Running one here costs nothing and waits for nothing: there is no model in it, because the
script already holds every step. You can read it before it runs, and what it does on the way
is approved and recorded exactly as if you had asked for it in words.

## Extending it

- **Playbooks** teach it one app at a time: where the button actually is, what the
  confirmation looks like. Only the name is in the prompt until that app is in front.
- **MCP servers** put tools that are not on this phone alongside the ones that are. Streamable
  HTTP with a bearer token or none; OAuth sign-in and stdio servers are not supported.

## How it works

```
MainActivity (Compose)          AgentSession (singleton)         ControlService
  prompt field                    StateFlow<List<ChatThread>>      (AccessibilityService)
  transcript          ──────►     AgentLoop ─► ToolPipeline ──────►  screenshot
  settings                          Router (which model)            ui tree
  enable-a11y button                Compactor                       gesture / text / global
                                    Approvals / Plans / Questions   foreground package
                                  Chats ─► SessionStore
                                    events on disk, projected
```

`ControlService` is the process that matters: it owns the coroutine scope the run lives in and
posts an ongoing notification with a Stop action. `MainActivity` is a thin view over shared
state, so closing it does not stop a run. There is no foreground service - an enabled
accessibility service is already persistent, system-bound and exempt from background limits,
and since gestures need an awake, unlocked screen, Doze never applies during a run.

### What the accessibility service buys

| Need | Mechanism | Min API |
|---|---|---|
| See the screen | `AccessibilityService.takeScreenshot()` | 30 |
| Read UI semantically | `rootInActiveWindow` node tree | 16 |
| Tap / swipe | `dispatchGesture()` | 24 |
| Back / Home / Recents | `performGlobalAction()` | 16 |
| Type | `ACTION_SET_TEXT` on the focused node | 21 |
| Stay alive across a long run | the a11y service is system-bound and exempt from background limits | - |

`takeScreenshot()` needs `android:canTakeScreenshot="true"` in the service config, and
returns a `HardwareBuffer` that **must be closed** or subsequent calls fail. It is also
rate-limited - `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` - so captures are throttled.

**minSdk is 30**, set by `takeScreenshot`. That conveniently also means `java.time`,
`Optional` and streams are available natively, with no desugaring.

### The tools

| Tool | Notes |
|---|---|
| `screenshot` | returns an image block; throttled, downscaled, JPEG |
| `ui_tree` | indexed list of interactive/labelled nodes with bounds |
| `tap` | by `x,y` **or** by `node` index from `ui_tree` |
| `swipe` | `x1,y1 → x2,y2` over `duration_ms` |
| `type_text` | into the focused node; falls back to a node index |
| `press` | `back` / `home` / `recents` / `notifications` |
| `open_app` | by package name or visible label |
| `list_apps` | launchable packages + labels |
| `wait` | sleep between UI transitions |
| `run_steps` | a short run of certain actions in one turn, each still gated and logged |
| `run_script` | a Lua script, deciding as it goes, under a step and time budget |
| `save_script` / `call_script` | keep one under a name, then call it with arguments |
| `ask_user_question` | ask the user something and wait; the run stays where it is |
| `todo_write` | the checklist for this task, which the user sees as it changes |
| `session_search` / `session_read` | look things up in this chat's own history |
| `skill` | load one app playbook by name |
| `remember` / `forget` | edit the durable notes in the system prompt |
| `propose_plan` | plan mode only: the plan, for the user to approve |
| `done` | explicit finish with a summary |
| `mcp__<server>__<tool>` | whatever the configured MCP servers offer |

`ui_tree` before `screenshot` is the cheap path - text beats image tokens. The system
prompt tells the model to prefer it and reach for pixels only when the tree is unhelpful.

### One path for every call

    describe -> rules and approval -> run, under a timeout -> cap the result -> record both

In that order, including the sub-steps inside a `run_steps` batch and every action a Lua
script takes - otherwise batching becomes a way to launder unapproved taps. A `done` call is
logged like any other even though the transcript renders its summary instead of a pill: a chat
that does not record the call that ended it cannot be continued, because the provider sees a
call nothing answered.

### On disk

Each chat is its own directory - a small metadata file rewritten atomically, and
`session.jsonl`, so one interrupted write cannot take every chat with it. A tool call and its result are separate events, as are a question
and its answer, so the file is only ever appended to.

Only the metadata is read at startup, so launching costs the same with two hundred chats as
with two; a log is read when its chat is opened or run, and trimmed to its last few thousand
events once it passes a couple of megabytes. The title and the last line live on the metadata
for the same reason - the chat list must never have to parse a conversation to draw itself.
The metadata also records whether a run is in flight: found set at startup, it means the app
was killed mid-run, and the chat flags it and offers to pick it up.

### Staying inside the context window

A run that taps through thirty screens accumulates thirty accessibility trees. `Compactor`
drops the body of stale tool results first - the biggest and least useful thing in the
history, since the tree from twelve taps ago describes a screen nobody is looking at - and
only then asks the model to summarise the oldest blocks. Whole blocks move together: a tool
result without the call that produced it is a 400, as are two user messages in a row. What
compaction drops is still on disk, so `session_search` and `session_read` can go back and find
it. Screenshots are never replayed - by the next turn that screen is several actions out of
date.

Images are the awkward part of the wire format: content parts are legal only on the `user`
role, so a screenshot cannot sit inside a tool result. The tool message carries a text
placeholder and the image follows as a user message.

## Reporting a bug

Settings → Diagnostics → Export. It bundles the current chat, your settings and the state of
the permissions into a file you can send. It never includes your API key, any server token, or
anything typed into a password field.

The key lives in `EncryptedSharedPreferences`, is masked in the UI and in the accessibility
tree, and goes only to the endpoint you configured. Text typed into a password field never
reaches the transcript, the log or an approval prompt - the node is marked as a password and
the detail becomes `(hidden)` - though the model still gets the argument, since it is the one
typing it. Screenshots of whatever is on screen do go to whichever provider you configured, which the
app states on first run.

## Building

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # signed with sideload.jks, see app/build.gradle.kts
./gradlew :app:testDebugUnitTest      # unit tests
./eval/run.py --model google/gemini-2.5-flash   # scripted tasks against a device
```

`eval/README.md` covers the task suite and the emulator container in `docker/`.
