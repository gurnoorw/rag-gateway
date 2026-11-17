# docs/img

Images the top-level README shows. One slot so far:

- `grafana-dashboard.png`: the `rag-gateway` Grafana dashboard with the app taking traffic, captured during the laptop run (release runbook, step 6). It is not committed yet, so the README links no image.

Once the file is committed, the README's Dashboard section links it with alt text:

```markdown
![Grafana dashboard "rag-gateway": request rate, latency, overhead, tokens, cost, cache, breaker and RAG panels](docs/img/grafana-dashboard.png)
```

`python3 scripts/check_readme_numbers.py` fails on a README image whose file is not committed, in both modes, so the link and the file land in the same commit.
