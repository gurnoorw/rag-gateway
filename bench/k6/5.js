// Scenario 5 (t08): fallback with the primary killed mid-run. An open load on a Claude model; at KILL_EPOCH_MS
// (an absolute time chosen by run-all.sh) run-all.sh kills the mock-claude container, and the gateway must fall
// back to Ollama (mock-ollama). Each request is classed by its own start time: before or after the kill.
//   after_ok / after_total     -> success rate after the kill
//   before_duration / after_duration (successful requests only) -> added latency = after - before, per percentile
// Requests in flight at the kill are "before" requests; the ones the kill cuts mid-stream fail and are counted
// in before_total - before_ok. Errors are the measurement here, so no error threshold.
import { Counter, Gauge, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, openScenario, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const KILL_EPOCH_MS = Number(requiredEnv('KILL_EPOCH_MS'));
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '60s';

const runStart = new Gauge('run_start_epoch_ms');
const beforeDuration = new Trend('before_duration', true);
const afterDuration = new Trend('after_duration', true);
const beforeTotal = new Counter('before_total');
const beforeOk = new Counter('before_ok');
const afterTotal = new Counter('after_total');
const afterOk = new Counter('after_ok');
const afterByOllama = new Counter('after_served_by_ollama');

export const options = {
  scenarios: { fallback: openScenario(RATE, DURATION, 100, 1000) },
  summaryTrendStats: TREND_STATS,
};

export function setup() {
  runStart.add(Date.now());
}

export default function () {
  const started = Date.now();
  const res = postChat(KEY, chatBody('claude-haiku-5-5', shortPrompt(`fallback-${exec.scenario.iterationInTest}`), 0.7));
  const ok = streamOk(res);
  if (started < KILL_EPOCH_MS) {
    beforeTotal.add(1);
    if (ok) { beforeOk.add(1); beforeDuration.add(res.timings.duration); }
  } else {
    afterTotal.add(1);
    if (ok) {
      afterOk.add(1);
      afterDuration.add(res.timings.duration);
      if (res.headers['X-Gateway-Provider'] === 'ollama') afterByOllama.add(1);
    }
  }
}
