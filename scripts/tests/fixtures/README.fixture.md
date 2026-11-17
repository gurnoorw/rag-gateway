# Fixture README for scripts/tests (test data, not a measurement)

Some prose with a figure: the timeout is 5 s and the cache TTL is 6 h.

<!-- designed-for:begin -->
| Quantity | Assumption or target | Source |
|---|---|---|
| Peak request rate | ~900 requests/s | C2 |
<!-- designed-for:end -->

<!-- measured:begin -->
| Metric | Value | Unit | Measured on | Run | Source key |
|---|---|---|---|---|---|
| Overhead requests | 10,234 | requests | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.overhead.requests` |
| TTFB added p50 (k6 client-side) | 1.0 | ms | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.overhead.added_latency_seen_by_client_ms.ttfb.p50` |
| TTFB added p99 (k6 client-side) | 12.3 | ms | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.overhead.added_latency_seen_by_client_ms.ttfb.p99` |
| Processing overhead p99 (bucket edges 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000 ms) | 4.1 | ms | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.overhead.gateway_processing_overhead_ms.p99` |
| Measured hit rate | 49.9 | % | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.cache_hit_rate.measured_hit_rate` |
| Cost per 1,000 requests, baseline (modelled from list prices on a synthetic workload) | 0.1235 | USD | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.cost_per_1k.baseline_usd` |
| Cost per 1,000 requests, both (modelled from list prices on a synthetic workload) | 0.0051 | USD | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.cost_per_1k.cache_and_routing_usd` |
| Fallback killed at | 30.0 | s | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.fallback.primary_killed_at_s` |
| Fallback success rate | 98.8 | % | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.fallback.success_rate_after_kill` |
| Fallback added p95 (k6 client-side) | 450.6 | ms | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.fallback.added_latency_ms.p95` |
| Admitted tokens per minute | 1000 | tokens/min | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.rate_limit.admitted_tokens_per_min` |
| Open streams at N=1000: peak heap | 210.8 | MB | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.open_streams.runs[n=1000].peak_heap_used_mb` |
| Open streams, first run: errors | 0 | errors | test data, not a measurement | 20261116T2130Z-test-data | `scenarios.open_streams.runs[0].errors` |
| Cache-hit p99 (k6 client-side) | pending | ms | test data, not a measurement | | `scenarios.cache_hit_latency.p99_ms` |
| Log insert p99 (bucket edges 0.5, 1, 2, 5 ms) | pending laptop run | ms | test data, not a measurement | | `scenarios.log_insert.p99_ms` |
<!-- measured:end -->

<!-- eval:begin -->
| Metric | Value | Unit | Measured on | Embedder | Run | Source key |
|---|---|---|---|---|---|---|
| Recall@5, keyword, 400 tokens | 0.429 | fraction | test data, not a measurement | none | 20261117T0000Z-test-data | `retrieval[mode=keyword,chunk_target_tokens=400].recall_at_5` |
| MRR, keyword, 200 tokens | 21.4 | % | test data, not a measurement | none | 20261117T0000Z-test-data | `retrieval[mode=keyword,chunk_target_tokens=200].mrr` |
| ACL leaked chunks, keyword, 400 tokens | 0 | chunks | test data, not a measurement | none | 20261117T0000Z-test-data | `acl.unauthorised_leaked_chunks` |
| ACL questions | 6 | questions | test data, not a measurement | none | 20261117T0000Z-test-data | `acl.questions` |
| Recall@5, hybrid, 400 tokens | pending laptop run | fraction | test data, not a measurement | nomic-embed-text | | `retrieval[mode=hybrid,chunk_target_tokens=400].recall_at_5` |
| Answers correct | pending laptop run | fraction | test data, not a measurement | nomic-embed-text | | `answers.correct_fraction` |
<!-- eval:end -->
