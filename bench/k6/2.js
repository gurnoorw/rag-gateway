// Scenario 2 (t08): cache-hit latency. setup() warms a set of temperature-0 prompts (one miss each, which fills
// the cache); then an open load repeats only those prompts. cache_hit_duration holds the total time of responses
// the gateway marked X-Gateway-Cache: HIT; anything else is counted in cache_not_hit and reported, not hidden.
// The brief's target (p99 < 200 ms) is reported, not asserted: thresholds are on errors only.
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, openScenario, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const POOL = Number(__ENV.POOL || 20);
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '40s';
const hitDuration = new Trend('cache_hit_duration', true);
const notHit = new Counter('cache_not_hit');
const streamErrors = new Rate('stream_errors');

export const options = {
  scenarios: { hits: openScenario(RATE, DURATION, 50, 500) },
  summaryTrendStats: TREND_STATS,
  thresholds: { http_req_failed: ['rate==0'], stream_errors: ['rate==0'] },
  setupTimeout: '120s',
};

function body(i) {
  return chatBody('claude-haiku-5-5', shortPrompt(`cachehit-${i}`), 0);
}

export function setup() {
  for (let i = 0; i < POOL; i++) {
    const res = postChat(KEY, body(i));
    if (!streamOk(res)) throw new Error(`warm-up request ${i} failed with status ${res.status}`);
  }
}

export default function () {
  const res = postChat(KEY, body(exec.scenario.iterationInTest % POOL));
  streamErrors.add(!streamOk(res));
  if (res.headers['X-Gateway-Cache'] === 'HIT') hitDuration.add(res.timings.duration);
  else notHit.add(1);
}
