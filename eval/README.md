# RAG eval (t13)

How retrieval and answers are measured on the demo corpus, and what the labels can and cannot tell you. The method is DECISIONS.md D-53; the contracts are `questions.schema.json` and `results.schema.json`.

## The question set: `questions.v1.jsonl`

39 questions over `kubernetes/website` at `adaa04d017c75981660828f869b364a266b0ef53` (the pinned corpus, `data/corpus-manifest.tsv`):

| Kind | Count | What it tests |
|---|---|---|
| `conceptual` | 18 | a behaviour or reason, asked in other words than the page uses |
| `exact_term` | 10 | a field or flag name (`maxSurge`, `--cascade=orphan`, `imagePullPolicy`) |
| `unanswerable` | 5 | topics the Kubernetes docs do not cover (Kafka, PostgreSQL, DNSSEC, webpack, pandas); the expected answer is a refusal |
| `acl` | 6 | a question whose only answer is in a page the `support` key may not see |

**Gold labels.** Every answerable question names one document id and an evidence passage of 20–400 characters copied verbatim from that document's cleaned text: front matter, Hugo shortcodes and HTML comments removed by `scripts/corpus/load_corpus.py`, the same text the gateway indexes. Labels name documents and passages, not chunks, so one label works at every chunk size.

**Checking them.** `python3 scripts/eval/check_evidence.py` (after `scripts/corpus/fetch_corpus.py`) proves every passage is in its document. It imports the loader's `document()`, so cleaning has one implementation. It also checks the composition and that each `acl` gold document is invisible to the `support` group and visible to the question's `required_groups`. `EvalSetTest` checks each line against the schema.

**ACL questions.** Their gold documents are under `setup` (groups `eng`, `finance`) or `concepts` (`eng`). The unauthorised asker holds only `support`, which sees `tutorials`; the authorised asker holds the question's `required_groups` (`finance` for setup pages, `eng` for concepts pages).

## How a chunk is judged relevant

A retrieved chunk is relevant when it belongs to the gold document and contains the whole passage, or at least 60% of the passage's words as one unbroken run (`EvidenceMatcher`). The run rule covers a passage cut by a chunk boundary. A scattered subsequence is not used, because a long chunk holds most short passages' common words in order somewhere. The same words in another document are never relevant.

## Metrics

Computed over the 28 answerable questions (`conceptual` and `exact_term`), top 10 per question, asked with every group in the collection so ranking is measured apart from ACL:

- **recall@k**: share of questions with at least one relevant chunk in the top k (k = 1, 5, 10);
- **MRR**: mean of 1 / rank of the first relevant chunk, 0 when none is in the top 10;
- **nDCG@10**: binary relevance; the ideal ranking has min(relevant chunks in the collection, 10) hits. Smaller chunks can create more relevant chunks, so compare nDCG within a chunk size, not across sizes.

The ACL eval asks each `acl` question twice at 400 tokens with the RAG endpoint's default top 5. It reports:

- leaked chunks for the unauthorised key, counted by `AclAudit` from the chunk rows (must be 0);
- that key's no-answer share: questions where retrieval returned nothing, which is when the endpoint answers `no_answer: true` without a model call;
- the authorised key's recall@5.

Unanswerable questions are scored by refusal in the answer eval, not here.

## Running it

The eval reads the cleaned documents of the loaded `k8s-docs` collection and re-chunks them into tenant `eval`, collections `k8s-c200`, `k8s-c400`, `k8s-c800` (overlap 25/50/100, maximum = target), with the production chunker, chunk store and retriever.

```bash
python3 scripts/corpus/fetch_corpus.py && python3 scripts/eval/check_evidence.py
RAG_ADMIN_KEY=<admin key> python3 scripts/corpus/load_corpus.py --wait 600        # app running
./gradlew eval --args="retrieval --modes keyword --measured-on '<hardware label>'"
./gradlew eval --args="retrieval --modes keyword,vector,hybrid --embedder nomic-embed-text --measured-on '<hardware label>'"
```

- Each run writes `eval/results/<run_id>.json`, checked against `results.schema.json` before writing. The tracked tree must be clean, because the file names the commit that produced it.
- `--embedder none` (the default) runs keyword rows only. The vector column still needs values, so indexing fills it with the fake embedder, and those vectors are never compared.
- `--embedder fake` is a smoke run. It needs `--out build/eval/<file>.json`, and every row says "fake embedder, not a model". It never goes in `eval/results/` or the README.
- A second run reuses the eval index when nothing changed, so it repeats the same chunk ids and the same metrics.

## Answers: judge and deterministic checks

Answers are scored in two steps, so scoring (which can cost money) never regenerates them, and a dry run needs only a file. The rubric is `judge/rubric.md`, the judge prompt `judge/prompt.txt`, and the settings `judge/judge.properties`: model, prices, effort, max tokens, spend cap. Code: `src/eval/java/dev/raggateway/eval/judge/`.

1. **`answers collect`** asks `POST /v1/rag/query` (default collection `k8s-c400`, mode `hybrid`, top_k 5, the production setting) for every `conceptual`, `exact_term` and `unanswerable` question. `acl` questions belong to the ACL eval.
   - The key is `EVAL_GATEWAY_API_KEY`, a gateway key of tenant `eval` with the groups of the indexed documents.
   - Collect reads the text of every source chunk back from Postgres, because the response carries chunk ids and the judge needs the text to grade `grounded`.
   - It writes one JSON line per question. No judge is involved.
2. **`answers score`** runs with exactly one of `--dry-run` or `--confirm-spend`:
   - **Free checks:** unanswerable questions are scored by refusal and every answer's citations are checked against its sources (`DeterministicChecks`). Neither needs the judge.
   - **Judge:** answerable questions go to the judge one at a time, under the spend cap (`SpendCap`).
   - **`--dry-run`:** builds every judge prompt and prints the estimated tokens (3 characters per token) and the worst-case cost. It never builds a client.
   - **`--confirm-spend`:** builds the client, which reads its key from `EVAL_JUDGE_API_KEY` only. It scores until the cap would be exceeded and reports "scored N of M".
   - **Output:** with `--into eval/results/<run_id>.json`, the `answers` section is added to a committed run file and the whole file is re-validated against `results.schema.json`.
   - **When nothing is written:** if no verdict was accepted, or if any response ran in another retrieval mode than asked (a hybrid query that fell back to keyword), the command writes nothing.

In this container the judge is tested against WireMock only (`AnswersEndToEndIT` runs collect against the real endpoint with a mock generator, then a dry run). Answers are never collected or scored here, and nothing from this container goes into `eval/results/` for answers.

### On the owner's laptop

App running with real `nomic-embed-text` embeddings, the corpus loaded, and the retrieval run done with `--embedder nomic-embed-text` (that run's file is the one the answers section goes into). `EVAL_GATEWAY_API_KEY` and `EVAL_JUDGE_API_KEY` are set in `.env`.

```bash
./gradlew eval --args="answers collect --questions eval/questions.v1.jsonl --out build/eval/answers.jsonl"
./gradlew eval --args="answers score --answers build/eval/answers.jsonl --dry-run"
./gradlew eval --args="answers score --answers build/eval/answers.jsonl --confirm-spend --into eval/results/<run_id>.json"
#   optional: --cap-usd 0.20 (lower only), --calibration build/eval/labels.jsonl ({"id":"q01","correctness":2} per line)
```

- **Generator:** collect uses whatever the gateway routes to: Claude, or `llama3.2:1b` as the fallback. Record which one served the answers next to the results.
- **Calibration:** hand-label 10 answers from the collected file, then pass `--calibration`. The report gives the share where the judge's `correctness` equals the hand label.
- **Commit:** commit the run file with its new `answers` section. The README's answer cells need that file, with `nomic-embed-text` and a judge section (`check_readme_numbers.py`).

## Known biases and limits

- **Written from the passage.** Each question was written after reading its passage, which favours retrieval of that passage. Questions are paraphrased to avoid flattering keyword search, but some field-name questions have to use the field name. The owner reviews the set.
- **One gold document per question.** Another page that also answers the question counts as a miss. Kubernetes pages repeat each other (Deployment and ReplicaSet, the two CronJob policies), so recall is a lower bound.
- **Small set.** One question moves recall by 1/28 (about 0.036). Differences smaller than a few questions are noise.
- **Keyword ranking has no IDF.** `ts_rank_cd` weighs a common word like "pod" the same as a rare one (D-49), and with OR between terms most questions match many chunks. The keyword rows measure that ranking as it is.
- **Unauthorised no-answer share.** It is low by design: the `support` key can see the tutorials, and an OR query usually matches some tutorial chunk. That is not a leak. Whether the model then refuses is the answer eval's question.
- **Re-indexing changes tie order.** Chunk ids are random UUIDs, and both legs break ties by chunk id. Rebuilding the eval index from scratch can reorder tied chunks, so metrics are repeatable on one index, not across rebuilds.
