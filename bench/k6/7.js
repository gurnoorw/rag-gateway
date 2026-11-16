// Scenario 7 (t08, D-03, D-45): open streams. A closed model on purpose: N virtual users each hold one ~5 s
// stream at a time (the "bench-stream-5s" mock mapping), so N streams are open at once for HOLD; the question is
// "how many concurrent streams", not "what rate". run-all.sh steps N (e.g. 500, 1,000, 2,000, 4,000) with the app
// at -Xmx512m and -Djdk.tracePinnedThreads=short, and samples heap and virtual threads while it runs.
// errors = streams started - streams completed (a 200 that ends with [DONE]): a failed response, a k6 timeout
// and an iteration still unfinished when the step's graceful stop ends all count. Errors are the measurement,
// so no threshold.
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const N = Number(requiredEnv('N'));
const HOLD = __ENV.HOLD || '20s';
const started = new Counter('streams_started');
const completed = new Counter('streams_completed');

export const options = {
  scenarios: { streams: { executor: 'constant-vus', vus: N, duration: HOLD, gracefulStop: '30s' } },
  summaryTrendStats: TREND_STATS,
};

export default function () {
  const prompt = shortPrompt(`streams-bench-stream-5s-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`);
  started.add(1);
  const res = postChat(KEY, chatBody('claude-haiku-5-5', prompt, 0.7), '60s');
  if (streamOk(res)) completed.add(1);
}
