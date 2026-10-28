# BetterText compatibility integration

The application uses the `codex/rikkahub-search` branch of
[DuoHBshuijiao/bettertext](https://github.com/DuoHBshuijiao/bettertext/tree/codex/rikkahub-search),
based on upstream v0.1.3 (`aafb4cf15dee030e516fa204398097a6916acc8f`).
The version catalog pins the BOM and all four modules to the same immutable commit.
The fork retains the upstream licenses and exposes only general text/layout hooks;
application search behavior remains here.

## Boundaries

* `ui/markdown` retains Material defaults, stable search addresses, source-to-rendered
  offsets, highlighting and relocation. Parsed nodes come from BetterText.
* Each rendering instance owns a serial incremental parser. Snapshot observation and
  state updates stay on the UI thread; only parsing runs on `Dispatchers.Default`.
  Completed content uses a full parse, including after streaming stops without changing
  the source. This resolves link definitions that were appended after stable chunks.
* Formula/citation offsets map first from searchable text to the input `AnnotatedString`,
  then through BetterText's layout result into expanded inline layout coordinates.
  No search markers are inserted into stored messages, copies or exported files.
* Code line offsets, search-driven unfolding, table cell paths, details expansion,
  workspace images, quotation highlighting, font settings and the LaTeX switch remain.
* Markdown/JSON file serialization, the Web renderer, HTML preview and editable text
  fields keep their existing implementations. Color swatches are not enabled.

## Validation when builds and device checks are permitted

Keep the existing parser/search/TTS tests, migrate the real selection-toolbar test,
and cover completed-vs-streaming parsing in `MarkdownDocumentParserTest`.
The fork's `BetterTextLayoutResultTest` needs Android's `StaticLayout` and checks
formula expansion and zero-length decorations.

Device acceptance includes 30,000-character search relocation, repeated hits, table
cells, collapsed details and code, Chinese/emoji offsets, selection while generating,
cross-code copying without line numbers, long formulas, unsupported formula fallback,
custom fonts, and PNG exports. Do not infer a performance gain from parser structure.

The generated baseline and startup profiles still include old renderer symbols.
Recollect them after functional validation, alongside the existing baseline workflows;
do not replace the independently developed long-text editor benchmark.

This change does not bump application version 3.1.1 / 194.
