import { useState, type FormEvent } from "react";
import { useMessages } from "../i18n/context.ts";

/** 確定した言い直しの props。 */
interface ConfirmedRestatementProps {
  summary: string;
  /** 言い直し（AI が書いた文章）の言語 */
  language?: string;
}

/**
 * 確定した言い直し。要約として上に残る。
 * @param props 言い直しの文章
 */
export function ConfirmedRestatement({
  summary,
  language,
}: ConfirmedRestatementProps) {
  const messages = useMessages();
  const TEXT = messages.text;
  return (
    <div className="system restatement confirmed">
      <p className="lead">{TEXT.restatementLead}</p>
      <p className="restatement-text" lang={language}>
        {summary}
      </p>
    </div>
  );
}

/** 言い直しの確認の props。 */
interface RestatementProps {
  summary: string;
  revisionsLeft: number;
  busy: boolean;
  /** 戻れる質問があるか */
  canGoBack: boolean;
  onConfirm: () => void;
  onRevise: (note: string) => void;
  onRewrite: (summary: string) => void;
  onGoBack: () => void;
  onSendAsIs: () => void;
  /** 言い直し（AI が書いた文章）の言語 */
  language?: string;
}

/** 入力の状態。 */
type Mode = "idle" | "note" | "edit";

/**
 * 「つまり、こういうことですね。」の言い直しと、その確認操作。
 * 「少し違います」は一言で作り直し、「自分で書き直す」は文章を直接編集して確定する。
 * @param props 言い直しと操作の受け口
 */
export function Restatement({
  summary,
  revisionsLeft,
  busy,
  canGoBack,
  onConfirm,
  onRevise,
  onRewrite,
  onGoBack,
  onSendAsIs,
  language,
}: RestatementProps) {
  const messages = useMessages();
  const TEXT = messages.text;
  const [mode, setMode] = useState<Mode>("idle");
  const [note, setNote] = useState("");
  const [edited, setEdited] = useState(summary);

  const submitNote = (event: FormEvent) => {
    event.preventDefault();
    if (note.trim() !== "") onRevise(note.trim());
  };
  const submitEdit = (event: FormEvent) => {
    event.preventDefault();
    if (edited.trim() !== "") onRewrite(edited.trim());
  };

  return (
    <div className="system restatement">
      <div tabIndex={-1} data-autofocus className="focus-target">
        <p className="lead">{TEXT.restatementLead}</p>
        <p className="restatement-text" lang={language}>
          {summary}
        </p>
      </div>

      {mode === "idle" && (
        <>
          {revisionsLeft === 0 && (
            <p className="notice-inline">{TEXT.noRevisionsLeft}</p>
          )}
          <div className="actions">
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={onConfirm}
            >
              {TEXT.restatementOk}
            </button>
            {revisionsLeft > 0 ? (
              <button
                type="button"
                disabled={busy}
                onClick={() => setMode("note")}
              >
                {TEXT.restatementNo}
              </button>
            ) : (
              <>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => setMode("edit")}
                >
                  {TEXT.rewrite}
                </button>
                <button type="button" disabled={busy} onClick={onSendAsIs}>
                  {TEXT.sendAsIs}
                </button>
              </>
            )}
          </div>
        </>
      )}

      {mode === "note" && (
        <form onSubmit={submitNote} className="revision-form">
          <label htmlFor="revision-note">{TEXT.noteLabel}</label>
          <textarea
            id="revision-note"
            rows={3}
            maxLength={1000}
            autoFocus
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
          <div className="actions">
            <button
              type="submit"
              className="primary"
              disabled={busy || note.trim() === ""}
            >
              {TEXT.noteSubmit}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => setMode("idle")}
            >
              {TEXT.cancel}
            </button>
            <button
              type="button"
              className="link"
              disabled={busy}
              onClick={() => setMode("edit")}
            >
              {TEXT.rewrite}
            </button>
          </div>
        </form>
      )}

      {mode === "edit" && (
        <form onSubmit={submitEdit} className="revision-form">
          <label htmlFor="revision-edit">{TEXT.rewriteLabel}</label>
          <textarea
            id="revision-edit"
            rows={5}
            maxLength={2000}
            autoFocus
            value={edited}
            onChange={(event) => setEdited(event.target.value)}
          />
          <div className="actions">
            <button
              type="submit"
              className="primary"
              disabled={busy || edited.trim() === ""}
            >
              {TEXT.rewriteSubmit}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => setMode("idle")}
            >
              {TEXT.cancel}
            </button>
          </div>
        </form>
      )}

      {canGoBack && mode === "idle" && (
        <p>
          <button
            type="button"
            className="link"
            disabled={busy}
            onClick={onGoBack}
          >
            {TEXT.backToQuestions}
          </button>
        </p>
      )}
    </div>
  );
}
