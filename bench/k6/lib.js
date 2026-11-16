// Shared pieces of the t08 k6 scenarios (D-45). Every scenario script imports this file.
//
// Rules kept here so no scenario can break them:
//  * Thresholds fail a run on errors only, never on latency (a slow number is still a number to report).
//  * Trends export p50/p95/p99 and their count (summaryTrendStats), so the summary builder never estimates.
//  * Prompts carry markers the mock mappings match on (bench/lib/gen_mocks.py): "bench-long" (3,000 input
//    tokens reported), "bench-stream-5s" (a ~5 s stream), "bench-headers-delayed" (headers after 20 s).
import http from 'k6/http';

export const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
export const MOCK_CLAUDE = __ENV.MOCK_CLAUDE_URL || 'http://localhost:8081';
export const TREND_STATS = ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'count'];

// ~330 characters: the mock reports 100 input tokens for these, and the gateway's estimate (3 chars/token, D-11)
// is ~115, so the rate limiter's reservation is not an under-estimate.
const FILLER = 'Summarise the following note for an internal status page in two short sentences. ' +
  'The note says that the nightly export finished on time, that two retries were needed for the billing ' +
  'job, and that nothing else changed. Keep it neutral and do not add details that are not in the note. ';

// ~12,000 characters: above the routing rule's 2,000 estimated input tokens (D-42), so "auto" keeps it on the
// strong model; the mock reports 3,000 input tokens for it.
const LONG_FILLER = ('Background paragraph for a long request. ' + FILLER).repeat(36);

export function shortPrompt(id) {
  return `[${id}] ${FILLER}`;
}

export function longPrompt(id) {
  return `[${id}] bench-long ${LONG_FILLER}`;
}

export function requiredEnv(name) {
  const v = __ENV[name];
  if (!v) throw new Error(`missing environment variable ${name}`);
  return v;
}

export function chatBody(model, prompt, temperature, maxTokens = 512) {
  return JSON.stringify({
    model: model,
    stream: true,
    temperature: temperature,
    max_tokens: maxTokens,
    messages: [{ role: 'user', content: prompt }],
  });
}

export function postChat(key, body, timeout = '60s') {
  return http.post(`${GATEWAY}/v1/chat/completions`, body, {
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    timeout: timeout,
  });
}

// A streamed chat completion that ended normally: 200 and the closing [DONE] event (an error after the first
// token is a 200 with an error event and no [DONE], SseResponseSink).
export function streamOk(res) {
  return res.status === 200 && typeof res.body === 'string' && res.body.endsWith('data: [DONE]\n\n');
}

// An open-model (constant-arrival-rate) scenario. preAllocatedVUs covers rate x the longest expected request;
// maxVUs lets k6 add VUs when the target slows down instead of sending less (coordinated omission, D-45).
export function openScenario(rate, duration, preAllocated, maxVUs, extra = {}) {
  return Object.assign({
    executor: 'constant-arrival-rate',
    rate: rate,
    timeUnit: '1s',
    duration: duration,
    preAllocatedVUs: preAllocated,
    maxVUs: maxVUs,
  }, extra);
}
