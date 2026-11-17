# Answer rubric (t13, D-53)

How `POST /v1/rag/query` answers are scored. The judge prompt (`prompt.txt`) states the same rubric to the model; this file is the reference for people. Settings (model, prices, effort, max tokens, cap) are in `judge.properties`.

## Which questions are scored how

| Kind | Scored by | What counts |
|---|---|---|
| `conceptual`, `exact_term` | LLM judge | `correctness` 0/1/2 and `grounded`, below |
| `unanswerable` | `DeterministicChecks.isRefusal` | refused or not; no judge call |
| every question | `DeterministicChecks.citationsWithinSources` | each citation names a source the model saw, with the same chunk id |
| `acl` | the ACL eval (`AclEvalRunner`), not this one | leaks and no-answer share, asked under two keys |

## Judge fields

- **`correctness`** (integer, against the gold evidence passage):
  - **2**: states what the gold evidence says in answer to the question. Paraphrase and extra correct detail are fine.
  - **1**: partly right: incomplete, vague, or a correct statement mixed with a wrong one.
  - **0**: wrong, contradicts the evidence, off the question, or a refusal.
- **`grounded`** (boolean, against the text of the sources the answer cites, not the gold evidence): true when every factual claim is supported by that text. Claims with no citation make it false. A refusal that claims nothing is grounded.
- **`reason`**: one or two sentences.

The two are independent: an answer can be correct but ungrounded (right from the model's own knowledge, cited badly) or grounded but wrong (faithful to a source that doesn't answer the question).

## Refusal check (unanswerable questions)

A response is a refusal when `no_answer` is true (retrieval found nothing; no model call), or the answer starts with "I don't know" (straight or typographic apostrophe, any case) and cites no source. "I don't know" followed by an explanation still counts; an answer that cites a source does not.

## Validation and rejection

- The request asks for a structured verdict (`outputConfig(JudgeVerdict.class)`).
- The reply is then checked against `JudgeVerdict.SCHEMA`: `correctness` in {0, 1, 2}, `grounded` boolean, `reason` 1–1000 characters, no other fields.
- A reply that fails (no text block, invalid JSON, value out of range, a refusal or a reply cut off at `max_tokens`) is **rejected**: listed in the report with its reason, not counted in `scored` or any fraction. Its tokens still count toward the spend cap.

## Results fields (`answers` in `eval/results.schema.json`)

| Field | Meaning |
|---|---|
| `of` | answerable questions (`conceptual` + `exact_term`) |
| `scored` | answerable questions with an accepted verdict (fewer than `of` when the cap stopped the run or verdicts were rejected) |
| `correct_fraction` | share of scored answers with `correctness` 2 |
| `correctness_mean` | mean `correctness` over scored answers (0–2) |
| `grounded_fraction` | share of scored answers with `grounded` true |
| `citations_valid_fraction` | share of all collected answers (answerable and unanswerable) that pass the citation check |
| `unanswerable.questions` / `.refused` | unanswerable questions asked / refused |
| `calibration` | optional: answers hand-labelled by the owner and the share where the judge's `correctness` equals the hand label |
| `judge.spent_usd` | reported usage at the configured prices, plus the worst case of any call that failed without usage |

## Spend cap

Before each judge call, its worst case (input estimated at 3 characters per token over the system prompt, the user prompt and the verdict schema, times the input price, plus `max-tokens` times the output price) must fit in the cap minus what has been spent. Otherwise the call is not made and the run stops with "scored N of M". After each call, the reported usage is added. `--dry-run` prints the same estimate for every prompt and never builds a client.
