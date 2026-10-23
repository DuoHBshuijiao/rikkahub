export interface UserQuestion {
  id: string;
  question: string;
  selection: "single" | "multi";
  options: string[];
}
export interface AnswerOption {
  id: string;
  text: string;
  originalText?: string;
}
export interface QuestionDraft {
  questionId: string;
  options: AnswerOption[];
  selectedIds: string[];
  nextCustomIndex: number;
}

function invalid(path: string, reason: string): never {
  throw new Error(`${path}: ${reason}`);
}
function object(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    invalid(path, "expected an object");
  return value as Record<string, unknown>;
}
function text(value: unknown, path: string): string {
  if (typeof value !== "string") invalid(path, "expected a string");
  return value;
}
function optionalText(value: unknown, path: string) {
  return value == null ? null : text(value, path);
}
function parseObject(input: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(input);
  } catch {
    invalid("$", "invalid JSON");
  }
  return object(value, "$");
}

export function parseAskUser(input: string): UserQuestion[] {
  const raw = parseObject(input).questions;
  if (!Array.isArray(raw)) invalid("$.questions", "expected an array");
  if (!raw.length) invalid("$.questions", "at least one question is required");
  const questions = raw.map((entry, index): UserQuestion => {
    const path = `$.questions[${index}]`;
    const q = object(entry, path);
    const rawId = optionalText(q.id, `${path}.id`);
    const id = rawId?.trim() ? rawId : `q_${index + 1}`;
    const question = text(q.question, `${path}.question`);
    if (!question.trim()) invalid(`${path}.question`, "question must not be blank");
    const mode = optionalText(q.selection_type, `${path}.selection_type`);
    if (mode != null && !["text", "single", "multi"].includes(mode))
      invalid(`${path}.selection_type`, "expected single or multi");
    if (q.options != null && !Array.isArray(q.options))
      invalid(`${path}.options`, "expected an array of strings");
    const options = ((q.options ?? []) as unknown[]).map((option, i) => {
      const value = text(option, `${path}.options[${i}]`);
      if (!value.trim()) invalid(`${path}.options[${i}]`, "option must not be blank");
      return value;
    });
    return { id, question, selection: mode === "multi" ? "multi" : "single", options };
  });
  if (new Set(questions.map((q) => q.id)).size !== questions.length)
    invalid("$.questions", "question IDs must be unique");
  return questions;
}

export function newAskUserDraft(questions: UserQuestion[]): QuestionDraft[] {
  return questions.map((q) => ({
    questionId: q.id,
    options: [
      ...q.options.map((value, i) => ({ id: `${q.id}:s${i}`, text: value, originalText: value })),
      { id: `${q.id}:c0`, text: "" },
    ],
    selectedIds: [],
    nextCustomIndex: 1,
  }));
}
export function selectOption(
  question: UserQuestion,
  draft: QuestionDraft,
  optionId: string,
): QuestionDraft {
  if (!draft.options.some((o) => o.id === optionId)) invalid(optionId, "option does not exist");
  return {
    ...draft,
    selectedIds:
      question.selection === "single"
        ? [optionId]
        : draft.selectedIds.includes(optionId)
          ? draft.selectedIds.filter((id) => id !== optionId)
          : [...draft.selectedIds, optionId],
  };
}
export function editOption(
  question: UserQuestion,
  draft: QuestionDraft,
  optionId: string,
  value: string,
): QuestionDraft {
  if (!draft.options.some((o) => o.id === optionId)) invalid(optionId, "option does not exist");
  return {
    ...draft,
    options: draft.options.map((o) => (o.id === optionId ? { ...o, text: value } : o)),
    selectedIds: !value.trim()
      ? draft.selectedIds
      : question.selection === "single"
        ? [optionId]
        : [...new Set([...draft.selectedIds, optionId])],
  };
}
export function addOption(draft: QuestionDraft): QuestionDraft {
  return {
    ...draft,
    options: [...draft.options, { id: `${draft.questionId}:c${draft.nextCustomIndex}`, text: "" }],
    nextCustomIndex: draft.nextCustomIndex + 1,
  };
}
export function removeOption(draft: QuestionDraft, optionId: string): QuestionDraft {
  if (!draft.options.some((o) => o.id === optionId && o.originalText === undefined))
    invalid(optionId, "custom option does not exist");
  const next = {
    ...draft,
    options: draft.options.filter((o) => o.id !== optionId),
    selectedIds: draft.selectedIds.filter((id) => id !== optionId),
  };
  return next.options.some((o) => o.originalText === undefined) ? next : addOption(next);
}
export function restoreOption(draft: QuestionDraft, optionId: string): QuestionDraft {
  return {
    ...draft,
    options: draft.options.map((o) =>
      o.id === optionId && o.originalText !== undefined ? { ...o, text: o.originalText } : o,
    ),
  };
}
export function isComplete(questions: UserQuestion[], draft: QuestionDraft[]): boolean {
  return (
    questions.length > 0 &&
    draft.length === questions.length &&
    questions.every((q, i) => {
      const d = draft[i];
      const selected = d.options.filter((o) => d.selectedIds.includes(o.id));
      return (
        d.questionId === q.id &&
        selected.length > 0 &&
        selected.length === d.selectedIds.length &&
        selected.every((o) => o.text.trim()) &&
        (q.selection === "multi" || selected.length === 1)
      );
    })
  );
}
export function encodeAnswer(questions: UserQuestion[], draft: QuestionDraft[]): string {
  if (!isComplete(questions, draft))
    invalid("$.answers", "answer every question and fill the selected options");
  return JSON.stringify({
    version: 2,
    answers: Object.fromEntries(
      draft.map((q) => [
        q.questionId,
        q.options
          .filter((o) => q.selectedIds.includes(o.id))
          .map((o) => ({
            option_id: o.id,
            source: o.originalText === undefined ? "custom" : "suggested",
            text: o.text,
            ...(o.originalText === undefined ? {} : { original_text: o.originalText }),
          })),
      ]),
    ),
  });
}
export function answerTexts(input: string): Record<string, string[]> {
  return Object.fromEntries(
    Object.entries(object(parseObject(input).answers, "$.answers")).map(([id, value]) => [
      id,
      Array.isArray(value)
        ? value.map((item, i) =>
            text(object(item, `$.answers.${id}[${i}]`).text, `$.answers.${id}[${i}].text`),
          )
        : [text(value, `$.answers.${id}`)],
    ]),
  );
}

export function restoreDraft(
  input: string,
  questions: UserQuestion[],
  saved: string | null,
): QuestionDraft[] {
  try {
    const value = JSON.parse(saved ?? "null") as {
      input?: unknown;
      draft?: QuestionDraft[];
    } | null;
    const draft = value?.draft;
    if (value?.input !== input || !Array.isArray(draft) || draft.length !== questions.length)
      return newAskUserDraft(questions);
    for (let i = 0; i < questions.length; i++) {
      const d = draft[i];
      const q = questions[i];
      if (
        d.questionId !== q.id ||
        !Array.isArray(d.options) ||
        !Array.isArray(d.selectedIds) ||
        !Number.isInteger(d.nextCustomIndex) ||
        d.nextCustomIndex < 1
      )
        throw new Error("invalid draft");
      if (
        new Set(d.options.map((o) => o.id)).size !== d.options.length ||
        d.selectedIds.some((id) => !d.options.some((o) => o.id === id))
      )
        throw new Error("invalid option IDs");
      d.options.forEach((o) => {
        if (
          typeof o.id !== "string" ||
          typeof o.text !== "string" ||
          (o.originalText !== undefined && typeof o.originalText !== "string")
        )
          throw new Error("invalid option");
      });
      q.options.forEach((option, j) => {
        if (!d.options.some((o) => o.id === `${q.id}:s${j}` && o.originalText === option))
          throw new Error("changed suggestions");
      });
      if (!d.options.some((o) => o.originalText === undefined))
        throw new Error("missing custom input");
    }
    return draft;
  } catch {
    return newAskUserDraft(questions);
  }
}
