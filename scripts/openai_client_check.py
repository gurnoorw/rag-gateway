#!/usr/bin/env python3
"""AC-t01-12: the official OpenAI Python client completes a streaming call against the gateway.

Points the `openai` SDK at the gateway's base URL and checks:
  1. a stream:true chat completion yields at least one content chunk, a finish_reason,
     and (with stream_options.include_usage) a usage chunk, then ends cleanly on [DONE];
  2. a wrong API key raises openai.AuthenticationError (401 JSON error body, DECISIONS.md C15).

The gateway must be running with a provider it can reach (for the t01 check: a WireMock stub
standing in for Claude). Never point this at a real provider from CI.

Setup (once):
    python3 -m venv /tmp/rg-openai && /tmp/rg-openai/bin/pip install openai

Run:
    RAG_GATEWAY_API_KEY=<key printed by scripts/seed-demo.sh> \\
        /tmp/rg-openai/bin/python scripts/openai_client_check.py \\
        --base-url http://localhost:8080/v1 --model claude-haiku-5-5

Exit code 0 when every check passes, 1 otherwise.
"""

import argparse
import os
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default=os.environ.get("RAG_GATEWAY_BASE_URL", "http://localhost:8080/v1"))
    parser.add_argument("--model", default="claude-haiku-5-5")
    parser.add_argument("--prompt", default="Say hello in five words.")
    parser.add_argument("--skip-auth-check", action="store_true", help="skip the wrong-key check")
    args = parser.parse_args()

    api_key = os.environ.get("RAG_GATEWAY_API_KEY")
    if not api_key:
        print("FAIL: set RAG_GATEWAY_API_KEY to a gateway API key", file=sys.stderr)
        return 1

    try:
        import openai
    except ImportError:
        print("FAIL: the 'openai' package is not installed (pip install openai)", file=sys.stderr)
        return 1

    client = openai.OpenAI(base_url=args.base_url, api_key=api_key, max_retries=0, timeout=60)
    failures = []

    # 1. Streaming call.
    content_chunks = 0
    text = []
    finish_reason = None
    usage = None
    response_ids = set()
    try:
        stream = client.chat.completions.create(
            model=args.model,
            messages=[{"role": "user", "content": args.prompt}],
            max_tokens=64,
            stream=True,
            stream_options={"include_usage": True},
        )
        for chunk in stream:
            response_ids.add(chunk.id)
            if chunk.usage is not None:
                usage = chunk.usage
            for choice in chunk.choices:
                if choice.delta and choice.delta.content:
                    content_chunks += 1
                    text.append(choice.delta.content)
                if choice.finish_reason:
                    finish_reason = choice.finish_reason
    except openai.OpenAIError as e:
        failures.append(f"streaming call raised {type(e).__name__}: {e}")

    if not failures:
        if content_chunks < 1:
            failures.append("no content chunks received")
        if finish_reason is None:
            failures.append("no finish_reason received")
        if usage is None:
            failures.append("no usage chunk received although include_usage was set")
        if len(response_ids) != 1:
            failures.append(f"expected one completion id across chunks, got {sorted(response_ids)}")
        print(f"stream: {content_chunks} content chunks, finish_reason={finish_reason}, "
              f"usage={usage.model_dump() if usage else None}, id={next(iter(response_ids), None)}")
        print(f"text: {''.join(text)!r}")

    # 2. Wrong key: the SDK must map the 401 JSON body to its typed exception.
    if not args.skip_auth_check:
        bad = openai.OpenAI(base_url=args.base_url, api_key="rgw_wrong_key_for_check", max_retries=0, timeout=30)
        try:
            bad.chat.completions.create(model=args.model, messages=[{"role": "user", "content": "hi"}], stream=True)
            failures.append("wrong key was accepted")
        except openai.AuthenticationError as e:
            print(f"auth: wrong key -> {type(e).__name__} (status {e.status_code}, code {e.code})")
        except openai.OpenAIError as e:
            failures.append(f"wrong key raised {type(e).__name__}, expected AuthenticationError")

    for f in failures:
        print(f"FAIL: {f}", file=sys.stderr)
    if failures:
        return 1
    print("PASS: OpenAI Python client streamed a completion through the gateway")
    return 0


if __name__ == "__main__":
    sys.exit(main())
