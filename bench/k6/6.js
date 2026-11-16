// Scenario 6 (t08): one tenant driven above its tokens-per-minute limit (the "ratelimit" tenant of
// seed-bench.sql). Each request reserves its estimated input plus max_tokens (~620 tokens) and is billed ~350
// (100 in + 250 out from the mock), so RATE requests/s ask for far more than the limit admits. The k6 side checks
// every 429 for a Retry-After header holding a positive whole number of seconds; the counts of over-limit
// requests and admitted tokens come from request_log (bench/lib/summary.py). 429s are expected, so the only
// error threshold is on anything that is neither a completed stream nor a 429.
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, openScenario, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '120s';
const r429 = new Counter('rejected_429');
const r429WithRetryAfter = new Counter('rejected_429_with_retry_after');
const admitted = new Counter('admitted');
const unexpected = new Rate('unexpected_responses');

export const options = {
  scenarios: { ratelimit: openScenario(RATE, DURATION, 50, 500) },
  summaryTrendStats: TREND_STATS,
  thresholds: { unexpected_responses: ['rate==0'] },
};

export default function () {
  const res = postChat(KEY, chatBody('claude-haiku-5-5', shortPrompt(`ratelimit-${exec.scenario.iterationInTest}`), 0.7));
  if (res.status === 429) {
    r429.add(1);
    const retryAfter = res.headers['Retry-After'];
    if (retryAfter !== undefined && /^[1-9][0-9]*$/.test(retryAfter.trim())) r429WithRetryAfter.add(1);
    unexpected.add(false);
  } else if (streamOk(res)) {
    admitted.add(1);
    unexpected.add(false);
  } else {
    unexpected.add(true);
  }
}
