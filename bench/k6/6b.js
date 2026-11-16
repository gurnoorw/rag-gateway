// Scenario 6b (t08, D-17): Redis unavailable from start. run-all.sh starts the app once with Redis stopped
// (connection refused) and once with REDIS_HOST set to an address that drops packets (black-holed), and runs
// this load each time: the scenario 1 gateway leg, shorter, with errors counted instead of failing the run.
// The added latency is this run's percentiles minus scenario 1's gateway leg (computed in bench/lib/summary.py).
export { options, default } from './1-gateway.js';
