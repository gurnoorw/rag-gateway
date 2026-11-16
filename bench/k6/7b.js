// Scenario 7b (t08, D-37 c): headers delayed. The Claude mock holds even its response headers for 20 s (the
// "bench-headers-delayed" mapping), past the 5 s first-token timeout. The gateway stops waiting at the deadline
// and falls back to Ollama; the abandoned Claude calls each park a virtual thread and a socket until the headers
// arrive. Closed model at one N from scenario 7; run-all.sh samples heap and virtual threads while it runs.
//   fallback success rate = completed streams / requests started; errors = started - completed (an iteration
//   still unfinished when the graceful stop ends counts as an error).
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const N = Number(requiredEnv('N'));
const HOLD = __ENV.HOLD || '30s';
const requests = new Counter('requests');
const completed = new Counter('completed');
const servedByOllama = new Counter('served_by_ollama');

export const options = {
  scenarios: { delayed: { executor: 'constant-vus', vus: N, duration: HOLD, gracefulStop: '60s' } },
  summaryTrendStats: TREND_STATS,
};

export default function () {
  const prompt = shortPrompt(`delayed-bench-headers-delayed-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`);
  requests.add(1);
  const res = postChat(KEY, chatBody('claude-haiku-5-5', prompt, 0.7), '60s');
  if (streamOk(res)) {
    completed.add(1);
    if (res.headers['X-Gateway-Provider'] === 'ollama') servedByOllama.add(1);
  }
}
