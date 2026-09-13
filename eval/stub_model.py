#!/usr/bin/env python3
"""
A stand-in for an OpenAI-compatible endpoint, so the agent loop can be driven without a model.

It speaks the shapes a real provider does - streamed deltas, tool calls, usage - and answers
the non-streaming request compaction makes when it summarises itself. Being scripted is the
point: the paths worth testing here are the long ones, and against a real model those are
slow, expensive and different every time.

  ./eval/stub_model.py --steps 12 --chatter 4000
  ./eval/stub_model.py --plan plans/run-script.json

--plan replays an exact list of tool calls, for a task that is about one tool instead of
about the loop around it.

--chatter pads each reply with filler. Compaction drops old tool results first and only
summarises when that was not enough, so filler on the assistant's own turns - which nothing
can drop - is what forces the second stage to run
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SUMMARY = "SUMMARY-MARKER: earlier steps, summarised."


class Handler(BaseHTTPRequestHandler):
    options = None
    calls = 0
    summaries = 0
    saw_summary = False

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")

        # No tools and no streaming: this is compaction asking for a summary
        if not body.get("stream"):
            Handler.summaries += 1
            print(f"<- summary request #{Handler.summaries}", flush=True)
            return self.reply_json({
                "id": "stub-summary",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": SUMMARY},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 900, "completion_tokens": 30, "cost": 0.0},
            })

        messages = body.get("messages", [])
        if any(SUMMARY in json.dumps(m) for m in messages):
            Handler.saw_summary = True

        time.sleep(Handler.options.delay)
        Handler.calls += 1
        step = Handler.calls
        print(
            f"-> step {step} (messages={len(messages)}, summary in context={Handler.saw_summary})",
            flush=True,
        )

        # A plan sets out exactly which calls to make, for a task about one tool: a script,
        # a batch. Without one the stub looks, repeatedly, which is what a task about the
        # loop itself needs
        plan = Handler.options.plan_steps
        if plan:
            if step <= len(plan):
                entry = plan[step - 1]
                self.stream(entry["tool"], entry.get("args", {}), step)
            else:
                self.stream("done", {"summary": f"Finished after {step - 1} planned calls."}, step)
        elif step >= Handler.options.steps:
            self.stream("done", {"summary": f"Finished after {step} steps."}, step)
        else:
            self.stream("ui_tree", {}, step)

    def stream(self, tool, arguments, step):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def send(obj):
            self.wfile.write(f"data: {json.dumps(obj)}\n\n".encode())
            self.wfile.flush()

        content = f"Step {step}. " + "x" * Handler.options.chatter
        send({"id": f"stub-{step}", "choices": [{"index": 0, "delta": {"content": content}}]})
        send({"id": f"stub-{step}", "choices": [{"index": 0, "delta": {"tool_calls": [{
            "index": 0,
            "id": f"call_{step}",
            "type": "function",
            "function": {"name": tool, "arguments": json.dumps(arguments)},
        }]}}]})
        send({
            "id": f"stub-{step}",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}],
            "usage": {"prompt_tokens": 1000 + step * 500, "completion_tokens": 25, "cost": 0.0},
        })
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def reply_json(self, payload):
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument(
        "--plan",
        help="JSON file of [{tool, args}] to call in order, then done. Overrides --steps",
    )
    parser.add_argument("--steps", type=int, default=12, help="tool calls before it finishes")
    parser.add_argument("--delay", type=float, default=0.0, help="seconds per reply")
    parser.add_argument(
        "--chatter",
        type=int,
        default=0,
        help="characters of filler per reply, to push the context up quickly",
    )
    Handler.options = parser.parse_args()
    Handler.options.plan_steps = (
        json.loads(open(Handler.options.plan).read()) if Handler.options.plan else None
    )

    print(f"stub model on :{Handler.options.port} ({Handler.options.steps} steps)", flush=True)
    HTTPServer(("0.0.0.0", Handler.options.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
