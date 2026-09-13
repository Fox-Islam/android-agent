#!/usr/bin/env python3
"""
Run a suite of tasks against a real device or emulator and score what came back.

A prompt change, a new tool, a tweak to the approval heuristic - none of them can be shown
to be an improvement by trying them once by hand. This drives the debug build through adb:
configure the model, start each task in a fresh chat, wait for it to finish, then read the
transcript back and check it against what the task expected.

  export OPENROUTER_API_KEY=sk-...
  ./eval/run.py --model google/gemini-2.5-flash

Needs the debug APK installed, the accessibility service enabled, and the screen unlocked.
See eval/README.md for running it against the emulator in docker/
"""

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path

STUB = Path(__file__).parent / "stub_model.py"

PACKAGE = "com.foxislam.androidagent"
EVAL_RECEIVER = f"{PACKAGE}/.EvalReceiver"
REMOTE_DUMP = "/sdcard/Android/data/com.foxislam.androidagent/files/agent-eval.json"


class Device:
    def __init__(self, serial=None):
        self.prefix = ["adb"] + (["-s", serial] if serial else [])

    def shell(self, command):
        result = subprocess.run(
            self.prefix + ["shell", command],
            capture_output=True,
            text=True,
            timeout=120,
        )
        return result.stdout.strip()

    def broadcast(self, extras):
        args = " ".join(f"--es {key} {shlex.quote(str(value))}" for key, value in extras.items())
        return self.shell(f"am broadcast -a {PACKAGE}.EVAL -n {EVAL_RECEIVER} {args}")

    def dump(self):
        """The transcript as the app sees it, or None if it has not written one yet."""
        self.broadcast({"op": "dump"})
        time.sleep(0.4)
        raw = self.shell(f"cat {REMOTE_DUMP}")
        if not raw.startswith("{"):
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return None

    def awake(self):
        self.shell("input keyevent KEYCODE_WAKEUP")
        self.shell("wm dismiss-keyguard")


class Stub:
    """
    The scripted endpoint from stub_model.py, started for the tasks that ask for one.

    `adb reverse` is what makes it reachable: the device gets a localhost port of its own
    that lands back here, which works for a USB phone and a local emulator alike. An
    emulator inside a container cannot use it - adb is on the far side - so point
    --stub-url at a stub you started there instead
    """

    def __init__(self, device, port, options):
        self.device = device
        self.port = port
        self.options = options
        self.process = None

    def __enter__(self):
        self.process = subprocess.Popen(
            [sys.executable, str(STUB), "--port", str(self.port)] + self.options,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        subprocess.run(
            self.device.prefix + ["reverse", f"tcp:{self.port}", f"tcp:{self.port}"],
            capture_output=True,
            timeout=30,
        )
        time.sleep(1)
        return f"http://127.0.0.1:{self.port}/v1/chat/completions"

    def __exit__(self, *_):
        subprocess.run(
            self.device.prefix + ["reverse", "--remove", f"tcp:{self.port}"],
            capture_output=True,
            timeout=30,
        )
        if self.process:
            self.process.terminate()


def _final_text(entries):
    return next(
        (e["text"] for e in reversed(entries) if e["kind"] in ("Finished", "Failed")),
        "",
    )


def _missing(needles, haystack, describe):
    """A failure line for every needle the haystack does not contain, case-insensitively."""
    return [describe(n) for n in needles if n.lower() not in haystack.lower()]


def _present(needles, haystack, describe):
    """A failure line for every needle the haystack does contain, case-insensitively."""
    return [describe(n) for n in needles if n.lower() in haystack.lower()]


def _finish_failures(expect, kinds):
    if expect.get("finished", True) and "Finished" not in kinds:
        return ["never called done"]
    return []


def _summary_failures(expect, final):
    return (
        _missing(expect.get("contains", []), final, lambda n: f"summary is missing {n!r}")
        + _present(expect.get("not_contains", []), final, lambda n: f"summary mentions {n!r}")
    )


def _tool_failures(expect, tools_used):
    return (
        [f"never used {t}" for t in expect.get("tools", []) if t not in tools_used]
        + [f"used {t}" for t in expect.get("not_tools", []) if t in tools_used]
    )


def _run_failures(expect, asked, tools_used):
    failures = []
    if expect.get("asks") and not asked:
        failures.append("never asked the user anything")
    limit = expect.get("max_steps")
    if limit is not None and len(tools_used) > limit:
        failures.append(f"took {len(tools_used)} steps, limit {limit}")
    return failures


# Tools that change the phone. `not_touched` is matched against these and not against what
# was seen, so reading a screen that contains the word does not count as acting on it
ACTING = {"tap", "swipe", "type_text", "press", "open_app", "run_script", "call_script", "run_steps"}


def _touched(entries):
    """The acting calls and what they named, without the results they came back with."""
    return " ".join(
        e["text"].split(" -> ")[0] for e in entries if e.get("tool") in ACTING
    )


def _report_failures(expect, entries):
    # What the tools reported back: a script's own log appears here and nowhere else
    results = " ".join(e["text"] for e in entries if e.get("tool"))
    # Notes carry what happened to the run itself - compaction, an interruption, a warning -
    # and are the only place those can be asserted on
    notes = " ".join(e["text"] for e in entries if e["kind"] == "Note")
    return (
        _missing(expect.get("results", []), results, lambda n: f"no tool result mentioning {n!r}")
        + _missing(expect.get("notes", []), notes, lambda n: f"no note about {n!r}")
        + _present(
            expect.get("not_touched", []), _touched(entries),
            lambda n: f"acted on something matching {n!r}",
        )
    )


def score(task, report):
    """Everything the task expected, and what actually happened."""
    expect = task.get("expect", {})
    entries = report.get("entries", [])
    final = _final_text(entries)
    tools_used = [e["tool"] for e in entries if e.get("tool")]
    asked = [e["text"] for e in entries if e["kind"] == "Question"]

    failures = (
        _finish_failures(expect, [e["kind"] for e in entries])
        + _summary_failures(expect, final)
        + _tool_failures(expect, tools_used)
        + _run_failures(expect, asked, tools_used)
        + _report_failures(expect, entries)
    )

    return {
        "task": task["name"],
        "passed": not failures,
        "failures": failures,
        "steps": len(tools_used),
        "tools": tools_used,
        "summary": final,
        "costUsd": report.get("costUsd", 0),
        "promptTokens": report.get("promptTokens", 0),
    }


def run_task(device, task, default_timeout):
    for command in task.get("setup", []):
        device.shell(command)
    device.awake()

    device.broadcast({"op": "run", "prompt": task["prompt"]})

    # Scripted replies to ask_user_question, in order. A suite runs unattended, so a task
    # that expects the agent to ask something has to say what the answer would have been
    answers = list(task.get("answers", []))

    deadline = time.time() + task.get("timeout", default_timeout)
    report = None
    while time.time() < deadline:
        time.sleep(5)
        report = device.dump()
        if not report:
            continue
        if report.get("asking") and answers:
            device.broadcast({"op": "answer", "text": answers.pop(0)})
            continue
        if not report.get("running", False):
            break
    else:
        device.broadcast({"op": "stop"})
        time.sleep(2)
        report = device.dump() or {"entries": []}
        result = score(task, report)
        result["failures"].insert(0, "timed out")
        result["passed"] = False
        return result

    return score(task, report or {"entries": []})


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb device serial, when more than one is attached")
    parser.add_argument("--model", default="google/gemini-2.5-flash")
    parser.add_argument("--key", default=os.environ.get("OPENROUTER_API_KEY", ""))
    parser.add_argument("--url", default="https://openrouter.ai/api/v1/chat/completions")
    parser.add_argument("--tasks", default=str(Path(__file__).parent / "tasks"))
    parser.add_argument("--timeout", type=int, default=240, help="per task, seconds")
    parser.add_argument("--only", help="run just the tasks whose name contains this")
    parser.add_argument(
        "--stub-url",
        help="a stub_model.py already running somewhere the device can reach, "
             "for setups where adb reverse cannot help (an emulator in a container)",
    )
    parser.add_argument("--stub-port", type=int, default=8770)
    parser.add_argument("--out", default=str(Path(__file__).parent / "results.json"))
    parser.add_argument("--baselines", default=str(Path(__file__).parent / "baselines.json"))
    parser.add_argument(
        "--update-baselines",
        action="store_true",
        help="write what this run did back to the baselines file",
    )
    return parser.parse_args()


def check_ready(device):
    """Checked once here, because otherwise every task fails the same way minutes apart."""
    if PACKAGE not in device.shell("pm list packages " + PACKAGE):
        sys.exit(f"{PACKAGE} is not installed on that device.")
    if PACKAGE not in device.shell("dumpsys accessibility | grep 'Enabled services'"):
        sys.exit(
            "The accessibility service is not enabled. Turn it on in Settings → "
            "Accessibility → android-agent, or:\n"
            f"  adb shell settings put secure enabled_accessibility_services "
            f"{PACKAGE}/{PACKAGE}.control.ControlService\n"
            "  adb shell settings put secure accessibility_enabled 1",
        )


def load_baselines(path):
    """What each model is already known to do, so a run can report what moved since."""
    file = Path(path)
    if not file.exists():
        return {}
    return json.loads(file.read_text())


# An expectation that only a run which did something can satisfy. Without one of these a
# task passes on a refusal, because answering in prose counts as finishing
EVIDENCE = ("tools", "results", "contains", "notes", "asks")


def load_tasks(directory, only):
    paths = sorted(Path(directory).glob("*.json"))
    if only:
        paths = [p for p in paths if only in p.stem]
    if not paths:
        sys.exit(f"No tasks in {directory}")
    tasks = []
    for path in paths:
        task = json.loads(path.read_text())
        task.setdefault("name", path.stem)
        if not any(task.get("expect", {}).get(k) for k in EVIDENCE):
            print(f"  {task['name']}: nothing here fails a run that did nothing - add one of {EVIDENCE}")
        tasks.append(task)
    return tasks


def configure(device, args, overrides=None):
    settings = {
        "url": args.url,
        "key": args.key,
        "models": args.model,
        "model": args.model,
        # Approvals are measured elsewhere, and a suite that stops to ask times out, so
        # the harness runs with them off
        "approvals": "AUTO",
    }
    settings.update(overrides or {})
    device.broadcast({"op": "config", "json": json.dumps(settings)})


def _stub_settings(task, url):
    return {**task.get("config", {}), "url": url, "models": "stub-model",
            "model": "stub-model", "key": "stub"}


def run_one(device, task, args):
    """One task, against whichever endpoint it asks for."""
    stub = task.get("stub")
    if stub is None:
        configure(device, args, task.get("config"))
        return run_task(device, task, args.timeout)
    if args.stub_url:
        configure(device, args, _stub_settings(task, args.stub_url))
        return run_task(device, task, args.timeout)
    options = [str(x) for pair in stub.items() for x in (f"--{pair[0]}", pair[1])]
    with Stub(device, args.stub_port, options) as url:
        configure(device, args, _stub_settings(task, url))
        return run_task(device, task, args.timeout)


def _moved(result):
    """
    Whether this result differs from the baseline, in either direction.

    A task marked flaky has no expected outcome, so neither result is movement. Marking one
    is a judgement about the task, which is why nothing here writes it automatically
    """
    baseline = result["baseline"]
    if baseline == "flaky":
        return False
    return result["passed"] != (baseline != "fail")


def report(results, args, baselines):
    cost = sum(r["costUsd"] for r in results)
    unexpected = [r for r in results if _moved(r)]
    passed = sum(1 for r in results if r["passed"])

    print(f"\n{passed}/{len(results)} passed, ${cost:.4f} total")
    for r in unexpected:
        moved = "now passes" if r["passed"] else "now fails"
        print(f"  {r['task']} {moved}, against a baseline of {r['baseline'] or 'pass'}")

    Path(args.out).write_text(json.dumps({"model": args.model, "results": results}, indent=2))
    print(f"Written to {args.out}")

    if args.update_baselines:
        known = baselines.setdefault(args.model, {})
        for r in results:
            # One run cannot tell a flaky task from a settled one, so flaky stays until
            # someone decides otherwise
            if known.get(r["task"]) == "flaky":
                continue
            known[r["task"]] = "pass" if r["passed"] else "fail"
        Path(args.baselines).write_text(json.dumps(baselines, indent=2, sort_keys=True) + "\n")
        print(f"Baselines for {args.model} updated in {args.baselines}")
        sys.exit(0)

    sys.exit(1 if unexpected else 0)


def main():
    args = parse_args()
    device = Device(args.serial)
    check_ready(device)
    tasks = load_tasks(args.tasks, args.only)

    # Stub-backed tasks reach no provider, so a key is only needed when one of these will
    if not args.key and any("stub" not in task for task in tasks):
        sys.exit("No API key: pass --key or set OPENROUTER_API_KEY.")

    baselines = load_baselines(args.baselines)
    known = baselines.get(args.model, {})

    results = []
    for task in tasks:
        print(f"→ {task['name']}: {task['prompt']}")
        result = run_one(device, task, args)
        configure(device, args)
        result["baseline"] = known.get(task["name"])
        results.append(result)

        mark = "pass" if result["passed"] else "FAIL"
        if result["baseline"] == "flaky":
            mark += " (flaky)"
        elif result["baseline"] == "fail":
            mark += " (expected)" if not result["passed"] else " (better than baseline)"
        print(f"  {mark}  {result['steps']} steps  ${result['costUsd']:.4f}")
        for failure in result["failures"]:
            print(f"        - {failure}")

    report(results, args, baselines)


if __name__ == "__main__":
    main()
