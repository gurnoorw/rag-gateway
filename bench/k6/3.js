// Scenario 3 (t08): cache hit rate on a workload with a stated repeat share. Every request is temperature 0 (all
// eligible). Request i (in arrival order) repeats a prompt from a pool of POOL prompts when i is even and is a
// new prompt when i is odd: a configured repeat share of 50 %. The pool starts cold, so each pool prompt's first
// arrival is a miss, and the measured rate is below the configured share by design. The measured rate is read
// from request_log (cache_status of this run's own tenant), and cross-checked against the response headers.
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, openScenario, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const POOL = Number(__ENV.POOL || 20);
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '20s';
const hits = new Counter('cache_hits');
const streamErrors = new Rate('stream_errors');

export const options = {
  scenarios: { hitrate: openScenario(RATE, DURATION, 100, 1000) },
  summaryTrendStats: TREND_STATS,
  thresholds: { http_req_failed: ['rate==0'], stream_errors: ['rate==0'] },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const prompt = i % 2 === 0 ? shortPrompt(`pool-${(i / 2) % POOL}`) : shortPrompt(`unique-${i}`);
  const res = postChat(KEY, chatBody('claude-haiku-5-5', prompt, 0));
  streamErrors.add(!streamOk(res));
  if (res.headers['X-Gateway-Cache'] === 'HIT') hits.add(1);
}
