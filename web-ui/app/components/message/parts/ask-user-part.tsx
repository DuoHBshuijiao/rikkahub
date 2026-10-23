import * as React from "react";
import { Loader2, MessageCircleQuestion } from "lucide-react";
import { Button } from "~/components/ui/button";
import { Textarea } from "~/components/ui/textarea";
import type { ToolPart as UIToolPart } from "~/types";
import {
  addOption,
  answerTexts,
  editOption,
  encodeAnswer,
  isComplete,
  parseAskUser,
  removeOption,
  restoreDraft,
  restoreOption,
  selectOption,
} from "~/lib/ask-user";
import type { AnswerOption, QuestionDraft, UserQuestion } from "~/lib/ask-user";
import { ControlledChainOfThoughtStep } from "../chain-of-thought";

export const AskUserConversationContext = React.createContext("");
interface Props {
  tool: UIToolPart;
  loading?: boolean;
  onToolApproval?: (
    id: string,
    approved: boolean,
    reason: string,
    answer?: string,
  ) => void | Promise<void>;
  isFirst?: boolean;
  isLast?: boolean;
}

export function AskUserToolStep({ tool, loading, onToolApproval, isFirst, isLast }: Props) {
  const [expanded, setExpanded] = React.useState(true);
  const [cancelling, setCancelling] = React.useState(false);
  const [cancelError, setCancelError] = React.useState("");
  const conversationId = React.useContext(AskUserConversationContext);
  const storageKey = `ask_user:${JSON.stringify([conversationId, tool.toolCallId])}`;
  const parsed = React.useMemo(() => {
    try {
      return { questions: parseAskUser(tool.input), error: null };
    } catch (error) {
      return { questions: null, error: error instanceof Error ? error.message : "参数有误" };
    }
  }, [tool.input]);
  const pending = !tool.output.length && tool.approvalState.type === "pending";
  const preparing = loading && !tool.output.length && tool.approvalState.type === "auto";
  const answered = React.useMemo(() => {
    if (tool.approvalState.type !== "answered") return null;
    try {
      return answerTexts(tool.approvalState.answer);
    } catch {
      return null;
    }
  }, [tool.approvalState]);
  React.useEffect(() => {
    if (tool.output.length || ["answered", "denied"].includes(tool.approvalState.type)) {
      try {
        sessionStorage.removeItem(storageKey);
      } catch {
        /* Storage can be unavailable. */
      }
    }
  }, [tool.output.length, tool.approvalState.type, storageKey]);
  const title = preparing
    ? "正在准备问题…"
    : !parsed.questions
      ? "提问参数有误"
      : parsed.questions.length === 1
        ? parsed.questions[0].question
        : `请回答 ${parsed.questions.length} 个问题`;
  return (
    <ControlledChainOfThoughtStep
      expanded={expanded}
      onExpandedChange={setExpanded}
      isFirst={isFirst}
      isLast={isLast}
      icon={
        preparing ? (
          <Loader2 className="size-4 animate-spin text-primary" />
        ) : (
          <MessageCircleQuestion className="size-4 text-primary" />
        )
      }
      label={<span className="text-foreground text-sm font-medium">{title}</span>}
    >
      <div className="w-full space-y-3">
        {preparing ? (
          <p>问题准备完成后即可回答</p>
        ) : tool.approvalState.type === "answered" ? (
          answered && parsed.questions ? (
            parsed.questions.map((q) => (
              <div key={q.id} className="space-y-1">
                <p className="font-medium">{q.question}</p>
                {answered[q.id]?.some((value) => value.trim()) ? (
                  answered[q.id].map((value, i) => (
                    <p key={i} className="text-primary whitespace-pre-wrap break-words">
                      {value}
                    </p>
                  ))
                ) : (
                  <p className="text-destructive">这道题没有保存的答案</p>
                )}
              </div>
            ))
          ) : (
            <p className="whitespace-pre-wrap break-words">{tool.approvalState.answer}</p>
          )
        ) : tool.approvalState.type === "denied" ? (
          <p>{tool.approvalState.reason || "已取消提问"}</p>
        ) : !parsed.questions ? (
          <div role="alert" className="space-y-2">
            <p className="text-destructive">无法显示这次提问，请让模型修正参数后重试。</p>
            <p className="text-muted-foreground text-xs break-words">{parsed.error}</p>
            {pending && onToolApproval && (
              <Button
                variant="secondary"
                disabled={cancelling}
                onClick={async () => {
                  setCancelling(true);
                  setCancelError("");
                  try {
                    await onToolApproval(tool.toolCallId, false, "提问参数无效，请修正后重新提问");
                  } catch (error) {
                    setCancelError(error instanceof Error ? error.message : "取消失败，请重试");
                  } finally {
                    setCancelling(false);
                  }
                }}
              >
                取消这次提问
              </Button>
            )}
            {cancelError && <p className="text-destructive">{cancelError}</p>}
          </div>
        ) : pending && onToolApproval ? (
          <PendingQuestions
            key={`${storageKey}:${tool.input}`}
            tool={tool}
            questions={parsed.questions}
            storageKey={storageKey}
            onSubmit={onToolApproval}
          />
        ) : (
          <p>{tool.output.length ? "这次提问已结束" : "等待问题准备完成"}</p>
        )}
      </div>
    </ControlledChainOfThoughtStep>
  );
}

function PendingQuestions({
  tool,
  questions,
  storageKey,
  onSubmit,
}: {
  tool: UIToolPart;
  questions: UserQuestion[];
  storageKey: string;
  onSubmit: NonNullable<Props["onToolApproval"]>;
}) {
  const [draft, setDraft] = React.useState(() => {
    let saved: string | null = null;
    try {
      saved = sessionStorage.getItem(storageKey);
    } catch {
      /* Use an in-memory draft. */
    }
    return restoreDraft(tool.input, questions, saved);
  });
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState("");
  const sending = React.useRef(false);
  React.useEffect(() => {
    try {
      sessionStorage.setItem(storageKey, JSON.stringify({ input: tool.input, draft }));
    } catch {
      /* Keep editing if storage is full. */
    }
  }, [storageKey, tool.input, draft]);
  const update = (id: string, next: QuestionDraft) =>
    setDraft((prev) => prev.map((q) => (q.questionId === id ? next : q)));
  const submit = async () => {
    if (sending.current || !isComplete(questions, draft)) return;
    sending.current = true;
    setSubmitting(true);
    setError("");
    try {
      await onSubmit(tool.toolCallId, true, "", encodeAnswer(questions, draft));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "提交失败，请重试");
    } finally {
      sending.current = false;
      setSubmitting(false);
    }
  };
  return (
    <div className="space-y-5">
      {questions.map((question, index) => (
        <QuestionEditor
          key={question.id}
          question={question}
          draft={draft[index]}
          disabled={submitting}
          onChange={(next) => update(question.id, next)}
        />
      ))}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      <Button disabled={submitting || !isComplete(questions, draft)} onClick={() => void submit()}>
        {submitting ? "正在提交…" : "提交答案"}
      </Button>
    </div>
  );
}

function QuestionEditor({
  question,
  draft,
  disabled,
  onChange,
}: {
  question: UserQuestion;
  draft: QuestionDraft;
  disabled: boolean;
  onChange: (draft: QuestionDraft) => void;
}) {
  const [editingId, setEditingId] = React.useState<string | null>(null);
  const selectionGroup = React.useId();
  return (
    <fieldset disabled={disabled} className="min-w-0 space-y-2">
      <legend className="text-sm font-medium">{question.question}</legend>
      <p className="text-muted-foreground text-xs">
        {question.selection === "single" ? "单选" : "多选"} · 点击选择，双击文字编辑
      </p>
      <div className="space-y-0.5 overflow-hidden rounded-2xl">
        {draft.options.map((option) => (
          <OptionRow
            key={option.id}
            option={option}
            selectionGroup={selectionGroup}
            multi={question.selection === "multi"}
            selected={draft.selectedIds.includes(option.id)}
            editing={editingId === option.id}
            disabled={disabled}
            onSelect={() => onChange(selectOption(question, draft, option.id))}
            onEdit={() => setEditingId(option.id)}
            onText={(value) => onChange(editOption(question, draft, option.id, value))}
            onDone={() => setEditingId(null)}
            onRemove={() => {
              onChange(removeOption(draft, option.id));
              setEditingId(null);
            }}
            onRestore={() => onChange(restoreOption(draft, option.id))}
          />
        ))}
      </div>
      <Button
        variant="ghost"
        onClick={() => {
          const next = addOption(draft);
          onChange(next);
          setEditingId(next.options.at(-1)!.id);
        }}
      >
        添加选项
      </Button>
    </fieldset>
  );
}

function OptionRow({
  option,
  selectionGroup,
  multi,
  selected,
  editing,
  disabled,
  onSelect,
  onEdit,
  onText,
  onDone,
  onRemove,
  onRestore,
}: {
  option: AnswerOption;
  selectionGroup: string;
  multi: boolean;
  selected: boolean;
  editing: boolean;
  disabled: boolean;
  onSelect: () => void;
  onEdit: () => void;
  onText: (value: string) => void;
  onDone: () => void;
  onRemove: () => void;
  onRestore: () => void;
}) {
  const textarea = React.useRef<HTMLTextAreaElement>(null);
  const clickTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelClick = () => {
    if (clickTimer.current != null) clearTimeout(clickTimer.current);
    clickTimer.current = null;
  };
  React.useEffect(() => () => cancelClick(), []);
  React.useEffect(() => {
    if (editing) textarea.current?.focus();
  }, [editing]);
  return (
    <div className="bg-muted/60 flex min-w-0 items-start gap-1 p-2">
      <label className="flex size-12 shrink-0 cursor-pointer items-center justify-center">
        <input
          type={multi ? "checkbox" : "radio"}
          name={multi ? undefined : selectionGroup}
          checked={selected}
          onChange={onSelect}
          disabled={disabled}
          aria-label={`选择 ${option.text || "自定义答案"}`}
          data-testid={`ask-select-${option.id}`}
          className="size-5 cursor-pointer accent-primary focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
        />
      </label>
      <div className="min-w-0 flex-1">
        {editing ? (
          <div className="space-y-1">
            <Textarea
              ref={textarea}
              value={option.text}
              onChange={(event) => onText(event.target.value)}
              disabled={disabled}
              aria-label={option.originalText === undefined ? "自定义答案" : "修改选项"}
              aria-invalid={selected && !option.text.trim()}
              data-testid={`ask-input-${option.id}`}
              className="max-h-64 resize-y"
            />
            <div className="flex flex-wrap gap-1">
              <Button variant="ghost" size="sm" onClick={onDone}>
                完成
              </Button>
              {option.originalText === undefined ? (
                <Button variant="ghost" size="sm" onClick={onRemove}>
                  移除
                </Button>
              ) : (
                option.text !== option.originalText && (
                  <Button variant="ghost" size="sm" onClick={onRestore}>
                    还原
                  </Button>
                )
              )}
            </div>
          </div>
        ) : (
          <button
            type="button"
            disabled={disabled}
            data-testid={`ask-text-${option.id}`}
            className="text-foreground w-full min-h-12 whitespace-pre-wrap break-words rounded-lg px-1 py-3 text-left text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
            aria-label={option.text || "填写自定义答案"}
            aria-description="点击选择，双击文字或按 F2 编辑"
            onClick={(event) => {
              if (!option.text.trim()) {
                onEdit();
                return;
              }
              if (event.detail === 0) {
                onSelect();
                return;
              }
              cancelClick();
              if (event.detail === 1) clickTimer.current = setTimeout(onSelect, 300);
            }}
            onDoubleClick={() => {
              cancelClick();
              onEdit();
            }}
            onKeyDown={(event) => {
              if (event.key === "F2") {
                event.preventDefault();
                onEdit();
              }
            }}
          >
            {option.text || <span className="text-muted-foreground">填写自定义答案</span>}
          </button>
        )}
        {selected && !option.text.trim() && (
          <p className="text-destructive text-xs" role="alert">
            请填写所选项的内容
          </p>
        )}
      </div>
    </div>
  );
}
