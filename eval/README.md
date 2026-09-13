# Eval

Scripted tasks, replayed against a device, scored from the transcript. The point is to be
able to tell whether a change to the prompt, the tool list or the approval heuristic made
the agent better or worse, instead of trying it once by hand and forming an impression.

## What you need

- The **debug** APK installed (`./gradlew :app:installDebug`) - the broadcast seam the
  harness drives is not in the release build.
- The accessibility service enabled, and the screen awake and unlocked.
- An API key: `export OPENROUTER_API_KEY=sk-...`

## Running

```sh
./eval/run.py --model google/gemini-2.5-flash
./eval/run.py --only settings          # one task
./eval/run.py --serial emulator-5554   # when several devices are attached
```

Results land in `eval/results.json`, and the exit code is non-zero if anything failed. The
device writes each transcript to `/sdcard/Android/data/com.foxislam.androidagent/files/`, which
needs no storage permission.

Approvals are forced to "never ask" for the duration of a suite - a task that stops to ask
a question nobody is there to answer just times out. Test the approval layer with unit
tests and by hand, not here.

## Against the emulator

`docker/` builds an image with the SDK, an emulator and an `agenttest` AVD baked in, so it
needs nothing mounted from the host. Build it once, start it, wait for the device to appear,
install and enable, then run the suite:

```sh
docker build -t android-emu docker/
docker run -d --name androidemu --device /dev/kvm \
  -p 5555:5555 -p 6081:6081 android-emu
adb connect localhost:5555
./gradlew :app:installDebug
adb shell settings put secure enabled_accessibility_services \
  com.foxislam.androidagent/com.foxislam.androidagent.control.ControlService
adb shell settings put secure accessibility_enabled 1
./eval/run.py
```

The emulator runs with no display at all - it has no usable GL surface in a container, and
scrcpy cannot mirror Android 35. To watch a suite run, `http://localhost:6081` serves a
screenshot a second, taken over adb.

## Tasks that need no model

Some of what needs testing is long, and against a real model long means slow, expensive
and different every time - compaction summarising a run, a resumed run finishing, the loop
surviving a provider that answers strangely. `stub_model.py` stands in for the endpoint and
answers to a script:

```sh
./eval/run.py --only compaction        # starts the stub itself, over adb reverse
```

A task asks for one by adding a `stub` block, whose keys are the stub's own options:

```json
{
  "stub": { "steps": 14, "chatter": 6000, "delay": 0.3 },
  "config": { "limit": 8000 },
  "expect": { "notes": ["stale tool results", "Summarised the earlier steps"] }
}
```

`config` overrides the app's settings for that task and is put back afterwards. `chatter`
pads each reply: compaction drops old tool results first and only summarises when that was
not enough, so filler on the assistant's own turns - which nothing can drop - is what makes
the second stage run. None of this needs an API key, and a suite of stub-only tasks will not
ask for one.

The stub is reached over `adb reverse`, which works for a USB phone and a local emulator. If
adb is somewhere the stub is not - an emulator inside a container, say - start the stub there
and point the runner at it:

```sh
docker cp eval/stub_model.py androidemu:/tmp/
docker exec -d androidemu python3 /tmp/stub_model.py --port 8770 --steps 14 --chatter 6000
./eval/run.py --only compaction --stub-url http://10.0.2.2:8770/v1/chat/completions
```

## Baselines

A cheap model fails some tasks by being a cheap model, and a suite that is permanently red
tells you nothing. `baselines.json` records what each model already does:

```json
{ "google/gemini-2.5-flash": { "asks-when-ambiguous": "fail" } }
```

A task is recorded as `pass`, `fail`, or `flaky` when it goes both ways for reasons that are
the model's rather than the app's. A run then reports only what moved: a task that fails as
its baseline records prints `FAIL (expected)`, a flaky one prints `pass (flaky)` or
`FAIL (flaky)`, and the exit code stays 0 for all of them. Anything that moves against a
`pass` or `fail` baseline is named in the summary and fails the run.

`--update-baselines` writes the current results back after you have looked at them, leaving
`flaky` alone - one run cannot tell a flaky task from a settled one.

## Writing a task

One JSON file per task in `eval/tasks`:

```json
{
  "prompt": "Open the Settings app and tell me the first item in the list.",
  "setup": ["am force-stop com.android.settings"],
  "timeout": 240,
  "expect": {
    "finished": true,
    "contains": ["network"],
    "not_contains": ["could not"],
    "tools": ["open_app", "ui_tree"],
    "results": ["3 items"],
    "not_tools": ["type_text"],
    "notes": ["stale tool results"],
    "max_steps": 12
  }
}
```

`results` matches against what the tools reported back, which is where a script's own log
ends up. `notes` matches against what the agent said about the run itself - compaction, an
interruption, a warning - which is the only way to assert on those. `setup` runs as adb shell
commands before the task, so a task can always start from the
same place. `expect` is checked against the final summary and the tools that were actually
called; `max_steps` is the thing that catches an agent that gets there eventually by
flailing, which no single run ever looks like.
