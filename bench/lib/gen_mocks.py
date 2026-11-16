#!/usr/bin/env python3
"""Writes the WireMock mappings under bench/wiremock/ (t08, D-45). Run it after changing a shape or a timing;
the generated JSON files are committed, so a run never depends on this script.

Shapes:
  * Claude: Messages-API SSE, the same events as the test fixture ClaudeSse (message_start with input tokens,
    one text block with text_delta pieces, message_delta with the stop reason and output tokens, message_stop).
  * Ollama: /api/chat NDJSON, the same lines as the test fixture OllamaNdjson (pieces, then done:true with
    prompt_eval_count / eval_count).

Timing (WireMock "fixedDelayMilliseconds" + "chunkedDribbleDelay"): WireMock waits the fixed delay, then for each
of the N byte-equal chunks sleeps totalDuration/N and writes the chunk; the status line and headers go out with
the first chunk. So the client's first byte arrives at fixed + total/N. The 1 s profile uses fixed 200 ms and 11
chunks over 1,100 ms: first chunk at 300 ms, the other 10 over the next 1 s.

Time to first byte must equal time to first token for both targets (D-45), so the first chunk has to contain
the first token (Claude: content_block_start and the first text_delta; Ollama: the first line). WireMock splits
by bytes, not events, so check_first_chunk() replays its split and fails the generation otherwise.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1] / "wiremock"

# One reply text, split into 50 pieces of about 20 characters: the size of typical streamed deltas.
SENTENCE = ("This is a fixed reply from the benchmark mock provider. It has no meaning and the same "
            "length for every request, so each run streams the same bytes. ")
PIECES = 50
OUTPUT_TOKENS = 250            # ~1,000 characters of text at ~4 characters per token
SHORT_INPUT_TOKENS = 100       # the k6 short prompts are ~330 characters
LONG_INPUT_TOKENS = 3000       # the k6 long prompts ("bench-long") are ~12,000 characters
MOCK_MODEL_CLAUDE = "claude-bench-mock"
MOCK_MODEL_OLLAMA = "llama3.2:1b"

# Profiles: (fixed delay ms, chunks, total dribble ms)
PROFILE_1S = (200, 11, 1100)      # first chunk at 300 ms, the rest over 1 s
PROFILE_5S = (100, 25, 5000)      # first chunk at 300 ms, the rest over 4.8 s (a ~5 s stream, scenario 7)
HEADERS_DELAY_MS = 20000          # scenario 7b: nothing, not even headers, for 20 s (> 5 s first-token timeout)


def pieces():
    text = (SENTENCE * 20)[: PIECES * 20]
    return [text[i:i + 20] for i in range(0, len(text), 20)]


def claude_body(input_tokens):
    def event(name, data):
        return f"event: {name}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"
    out = [event("message_start", {"type": "message_start", "message": {
        "id": "msg_bench_mock", "type": "message", "role": "assistant", "model": MOCK_MODEL_CLAUDE,
        "content": [], "stop_reason": None, "stop_sequence": None,
        "usage": {"input_tokens": input_tokens, "output_tokens": 1}}}),
        event("content_block_start", {"type": "content_block_start", "index": 0,
                                      "content_block": {"type": "text", "text": ""}})]
    first_token_end = None
    for p in pieces():
        out.append(event("content_block_delta", {"type": "content_block_delta", "index": 0,
                                                 "delta": {"type": "text_delta", "text": p}}))
        if first_token_end is None:
            first_token_end = len("".join(out).encode())
    out.append(event("content_block_stop", {"type": "content_block_stop", "index": 0}))
    out.append(event("message_delta", {"type": "message_delta",
                                       "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                                       "usage": {"output_tokens": OUTPUT_TOKENS}}))
    out.append(event("message_stop", {"type": "message_stop"}))
    return "".join(out), first_token_end


def ollama_body():
    created = "2026-11-16T10:00:00.000000Z"
    lines = [json.dumps({"model": MOCK_MODEL_OLLAMA, "created_at": created, "done": False,
                         "message": {"role": "assistant", "content": p}}, separators=(",", ":")) for p in pieces()]
    first_token_end = len(lines[0].encode()) + 1
    lines.append(json.dumps({"model": MOCK_MODEL_OLLAMA, "created_at": created, "done": True,
                             "message": {"role": "assistant", "content": ""}, "done_reason": "stop",
                             "total_duration": 1300000000, "load_duration": 1000000,
                             "prompt_eval_count": SHORT_INPUT_TOKENS, "prompt_eval_duration": 100000000,
                             "eval_count": OUTPUT_TOKENS, "eval_duration": 1000000000}, separators=(",", ":")))
    return "\n".join(lines) + "\n", first_token_end


def first_chunk_size(length, chunks):
    """WireMock's BodyChunker: length // chunks bytes per chunk, the remainder in the last one."""
    return length // chunks


def check_first_chunk(name, body, first_token_end, profile):
    size = first_chunk_size(len(body.encode()), profile[1])
    if size < first_token_end:
        raise SystemExit(f"{name}: first chunk ({size} B) ends before the first token ({first_token_end} B)")


def body_marker(marker):
    return [{"contains": marker}]


def mapping(name, priority, url, content_type, body, profile=None, marker=None, headers_delay=None):
    request = {"method": "POST", "urlPath": url}
    if marker:
        request["bodyPatterns"] = body_marker(marker)
    response = {"status": 200, "headers": {"Content-Type": content_type}, "body": body}
    if headers_delay is not None:
        response["fixedDelayMilliseconds"] = headers_delay
    else:
        fixed, chunks, total = profile
        response["fixedDelayMilliseconds"] = fixed
        response["chunkedDribbleDelay"] = {"numberOfChunks": chunks, "totalDuration": total}
    return {"name": name, "priority": priority, "request": request, "response": response}


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def main():
    claude = ROOT / "claude" / "mappings"
    ollama = ROOT / "ollama" / "mappings"
    claude.mkdir(parents=True, exist_ok=True)
    ollama.mkdir(parents=True, exist_ok=True)

    short, short_ft = claude_body(SHORT_INPUT_TOKENS)
    long_, long_ft = claude_body(LONG_INPUT_TOKENS)
    check_first_chunk("claude 1s", short, short_ft, PROFILE_1S)
    check_first_chunk("claude 1s long", long_, long_ft, PROFILE_1S)
    sse = "text/event-stream"
    write(claude / "messages-1s.json",
          mapping("claude: 1 s stream (default)", 10, "/v1/messages", sse, short, PROFILE_1S))
    write(claude / "messages-1s-long.json",
          mapping("claude: 1 s stream, long prompt (3,000 input tokens)", 5, "/v1/messages", sse, long_, PROFILE_1S,
                  marker="bench-long"))
    write(claude / "messages-5s.json",
          mapping("claude: 5 s stream (scenario 7)", 1, "/v1/messages", sse, short, PROFILE_5S,
                  marker="bench-stream-5s"))
    write(claude / "messages-headers-delayed.json",
          mapping("claude: headers held for 20 s (scenario 7b)", 1, "/v1/messages", sse, short,
                  marker="bench-headers-delayed", headers_delay=HEADERS_DELAY_MS))

    nd, nd_ft = ollama_body()
    check_first_chunk("ollama 1s", nd, nd_ft, PROFILE_1S)
    write(ollama / "chat-1s.json",
          mapping("ollama: 1 s stream (default)", 10, "/api/chat", "application/x-ndjson", nd, PROFILE_1S))


if __name__ == "__main__":
    main()
