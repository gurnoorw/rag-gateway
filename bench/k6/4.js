// Scenario 4 (t08): cost per 1,000 requests. One workload, run four times, each with its own fresh tenant so
// SUM(cost_usd) in request_log can be read per run without deletes:
//   baseline: explicit strong model (claude-opus-5-5), temperature 0.7 (not cacheable)
//   cache:    explicit strong model, temperature 0 (cacheable)
//   routing:  "auto" (rule-based routing, D-42), temperature 0.7
//   both:     "auto", temperature 0
// Workload (identical in every run, by arrival index i): even i repeats one of POOL pool prompts, odd i is a new
// prompt (50 % repeats); 20 % of the prompts are long (~12,000 characters; the mock reports 3,000 input tokens,
// short ones 100; output is 250 tokens), the rest short. Long prompts stay on the strong model under "auto".
// Costs are modelled from the list prices in gateway.models on this synthetic workload, not measured spend.
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { TREND_STATS, chatBody, longPrompt, openScenario, postChat, requiredEnv, shortPrompt, streamOk } from './lib.js';

const KEY = requiredEnv('KEY');
const VARIANT = requiredEnv('VARIANT');
const POOL = Number(__ENV.POOL || 20);
const RATE = Number(__ENV.RATE || 50);
// Exactly REQUESTS requests per run: the schedule runs slightly longer and iterations past REQUESTS do nothing,
// so the four runs log the same count (a constant-arrival-rate run can start one iteration more or less).
const REQUESTS = Number(__ENV.REQUESTS || 1000);
const DURATION = __ENV.DURATION || '21s';
const VARIANTS = {
  baseline: { model: 'claude-opus-5-5', temperature: 0.7 },
  cache: { model: 'claude-opus-5-5', temperature: 0 },
  routing: { model: 'auto', temperature: 0.7 },
  both: { model: 'auto', temperature: 0 },
};
const variant = VARIANTS[VARIANT];
if (!variant) throw new Error(`unknown VARIANT ${VARIANT}`);
const streamErrors = new Rate('stream_errors');

export const options = {
  scenarios: { cost: openScenario(RATE, DURATION, 100, 1000) },
  summaryTrendStats: TREND_STATS,
  thresholds: { http_req_failed: ['rate==0'], stream_errors: ['rate==0'] },
};

export function prompt(i) {
  if (i % 2 === 0) {
    const p = (i / 2) % POOL;
    return p % 5 === 0 ? longPrompt(`cost-pool-${p}`) : shortPrompt(`cost-pool-${p}`);
  }
  return i % 10 === 1 ? longPrompt(`cost-unique-${i}`) : shortPrompt(`cost-unique-${i}`);
}

export default function () {
  const i = exec.scenario.iterationInTest;
  if (i >= REQUESTS) return;
  const res = postChat(KEY, chatBody(variant.model, prompt(i), variant.temperature));
  streamErrors.add(!streamOk(res));
}
