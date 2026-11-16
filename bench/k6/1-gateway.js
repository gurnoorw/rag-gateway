// Scenario 1, gateway leg (t08, D-39, D-45): an open load (constant-arrival-rate) through the gateway, at least
// 10,000 requests (AC-t08-5). Same rate, duration and mock as 1-direct.js. Unique prompts and temperature 0.7,
// so the cache is not involved (only temperature 0 is cacheable, D-40); an explicit model, so routing is a no-op.
// run-all.sh scrapes gateway_overhead_seconds and gateway_requestlog_write_seconds before and after this leg for
// "gateway processing overhead" (D-39 a) and the log-insert cost (scenario 8).
//
// Scenario 6b runs this same script (shorter, TAG=6b-...) against an app started without Redis.
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, openScenario, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '110s';
// 6b reports its errors instead of failing on them (a dead Redis is the condition under test).
const FAIL_ON_ERRORS = (__ENV.FAIL_ON_ERRORS || 'true') === 'true';
const streamErrors = new Rate('stream_errors');
// started - completed also counts requests still running when the graceful stop ends (6b: a black-holed Redis
// can hold a request for tens of seconds), which http_reqs and stream_errors never see.
const started = new Counter('requests_started');
const completed = new Counter('requests_completed');

export const options = {
  scenarios: { gateway: openScenario(RATE, DURATION, 200, 2000, { gracefulStop: '60s' }) },
  summaryTrendStats: TREND_STATS,
  thresholds: FAIL_ON_ERRORS ? { http_req_failed: ['rate==0'], stream_errors: ['rate==0'] } : {},
};

export default function () {
  const prompt = shortPrompt(`${__ENV.TAG || 'gw'}-${exec.scenario.iterationInTest}`);
  started.add(1);
  const res = postChat(KEY, chatBody('claude-haiku-5-5', prompt, 0.7));
  const ok = streamOk(res);
  streamErrors.add(!ok);
  if (ok) completed.add(1);
}
