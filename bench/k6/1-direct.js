// Scenario 1, direct leg (t08, D-39, D-45): the same open load as 1-gateway.js, straight to the Claude mock.
// Its http_req_waiting (first byte = first token: the mock sends nothing before its first chunk) and
// http_req_duration are subtracted from the gateway leg's to give "added latency seen by the client".
import http from 'k6/http';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { MOCK_CLAUDE, TREND_STATS, openScenario, shortPrompt } from './lib.js';

const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '110s';
const streamErrors = new Rate('stream_errors');

export const options = {
  scenarios: { direct: openScenario(RATE, DURATION, 200, 2000) },
  summaryTrendStats: TREND_STATS,
  thresholds: { http_req_failed: ['rate==0'], stream_errors: ['rate==0'] },
};

export default function () {
  const body = JSON.stringify({
    model: 'claude-haiku-5-5',
    max_tokens: 512,
    stream: true,
    messages: [{ role: 'user', content: shortPrompt(`direct-${exec.scenario.iterationInTest}`) }],
  });
  const res = http.post(`${MOCK_CLAUDE}/v1/messages`, body, {
    headers: { 'Content-Type': 'application/json', 'anthropic-version': '2023-06-01' },
  });
  streamErrors.add(!(res.status === 200 && typeof res.body === 'string' && res.body.includes('event: message_stop')));
}
