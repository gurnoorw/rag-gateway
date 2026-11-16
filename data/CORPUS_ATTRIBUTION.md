# Corpus attribution

The RAG demo corpus is documentation from the Kubernetes website.

- **Source:** [kubernetes/website](https://github.com/kubernetes/website), commit
  `adaa04d017c75981660828f869b364a266b0ef53`: every `.md` file under `content/en/docs/concepts`,
  `content/en/docs/tutorials` and `content/en/docs/setup` (256 files, listed in `data/corpus-manifest.tsv`).
- **Authors:** The Kubernetes Authors.
- **Licence:** [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/),
  the `LICENSE` file of that repository at that commit. `scripts/corpus/fetch_corpus.py` checks the licence text
  and saves it as `data/corpus/LICENSE`.
- **Not redistributed here:** the content is fetched into the gitignored `data/corpus/` and is never committed.

## Changes made (the material is modified)

`scripts/corpus/load_corpus.py` changes each file before it is indexed:

- the Hugo front matter is removed; its `title` becomes the document title;
- Hugo shortcode tags (`{{< ... >}}`, `{{% ... %}}`) are removed, keeping the visible text of `glossary_tooltip`
  (`text="..."`) and `heading` shortcodes; the text between paired shortcodes is kept;
- HTML comments are removed and runs of blank lines are collapsed;
- files left empty by these steps (section index pages with front matter only) are skipped;
- each file is split into chunks and embedded for search; answers quote those chunks as citations.

The gateway adds access groups per section (concepts `eng`; tutorials `eng`, `support`; setup `eng`, `finance`) for
the access-control demo. These groups are this project's, not part of the source.
