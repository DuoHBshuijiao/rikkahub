import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
  addOption,
  answerTexts,
  editOption,
  encodeAnswer,
  isComplete,
  newAskUserDraft,
  parseAskUser,
  removeOption,
  restoreDraft,
  restoreOption,
  selectOption,
} from "../app/lib/ask-user";
import type { UserQuestion } from "../app/lib/ask-user";

const cases = JSON.parse(
  readFileSync(new URL("../../ai/src/test/resources/ask-user-cases.json", import.meta.url), "utf8"),
);
test("shared Kotlin/Web fixtures retain every valid question and report malformed fields", () => {
  for (const fixture of cases.valid)
    assert.deepEqual(parseAskUser(JSON.stringify(fixture.input)), fixture.expected);
  for (const fixture of cases.invalid)
    assert.throws(
      () => parseAskUser(JSON.stringify(fixture.input)),
      (error: unknown) => error instanceof Error && error.message.startsWith(fixture.path),
    );
  assert.throws(() => parseAskUser("{unfinished"), /invalid JSON/);
});
const single: UserQuestion = {
  id: "q",
  question: "问题",
  selection: "single",
  options: ["A", "A"],
};
const multi: UserQuestion = { ...single, selection: "multi" };
test("single edits select stable identities, preserve other drafts, and reject blank selections", () => {
  let draft = newAskUserDraft([single])[0];
  draft = selectOption(single, draft, "q:s0");
  draft = editOption(single, draft, "q:s1", "changed");
  assert.deepEqual(draft.selectedIds, ["q:s1"]);
  assert.equal(draft.options[0].text, "A");
  assert.equal(draft.options[1].originalText, "A");
  assert.ok(isComplete([single], [draft]));
  draft = editOption(single, draft, "q:s1", " ");
  assert.ok(!isComplete([single], [draft]));
  assert.throws(() => encodeAnswer([single], [draft]));
});
test("multi combines edited presets with independent custom answers without splitting commas", () => {
  let draft = newAskUserDraft([multi])[0];
  draft = editOption(multi, draft, "q:s0", "A, B");
  draft = editOption(multi, draft, "q:c0", "Windows, Linux\n第二行 😀");
  draft = editOption(multi, addOption(draft), "q:c1", "第三项");
  assert.deepEqual(answerTexts(encodeAnswer([multi], [draft])).q, [
    "A, B",
    "Windows, Linux\n第二行 😀",
    "第三项",
  ]);
  draft = removeOption(draft, "q:c0");
  assert.deepEqual(draft.selectedIds, ["q:s0", "q:c1"]);
  assert.deepEqual(selectOption(multi, draft, "q:s0").selectedIds, ["q:c1"]);
});
test("add and restore preserve selection, removal retains an empty custom input", () => {
  let draft = newAskUserDraft([single])[0];
  assert.deepEqual(addOption(draft).selectedIds, []);
  assert.equal(removeOption(draft, "q:c0").options.at(-1)!.id, "q:c1");
  draft = editOption(single, draft, "q:s0", "edited");
  draft = selectOption(single, draft, "q:s1");
  draft = restoreOption(draft, "q:s0");
  assert.equal(draft.options[0].text, "A");
  assert.deepEqual(draft.selectedIds, ["q:s1"]);
  assert.ok(!isComplete([], []));
});
test("draft recovery is bound to the original input and historical answers remain intact", () => {
  const input = "original input";
  const draft = [editOption(multi, newAskUserDraft([multi])[0], "q:c0", "回答")];
  const saved = JSON.stringify({ input, draft });
  assert.deepEqual(restoreDraft(input, [multi], saved), draft);
  assert.deepEqual(restoreDraft("different input", [multi], saved), newAskUserDraft([multi]));
  assert.deepEqual(restoreDraft(input, [multi], "broken"), newAskUserDraft([multi]));
  assert.deepEqual(answerTexts('{"answers":{"q":"A, B, 自定义"}}').q, ["A, B, 自定义"]);
});
