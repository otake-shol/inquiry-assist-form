import {
  CATEGORY_EXAMPLE_LIST,
  TAG_EXAMPLE_LIST,
  type MockExample,
} from "../text/mockExamples.ts";

/** モック表示の props。 */
interface MockBarProps {
  /** 例文が選ばれたときに呼ぶ */
  onPick: (text: string) => void;
  /** 例文ボタンを使えるか（入力画面でのみ true） */
  canPick: boolean;
}

/**
 * 「モック」バッジと、シナリオを試せる例文ボタン。
 * @param props 例文選択の受け口
 */
export function MockBar({ onPick, canPick }: MockBarProps) {
  const renderGroup = (title: string, examples: MockExample[]) => (
    <div className="mock-group" role="group" aria-label={title}>
      <span className="mock-group-title">{title}</span>
      {examples.map((example) => (
        <button
          key={example.label}
          type="button"
          className="chip"
          disabled={!canPick}
          onClick={() => onPick(example.text)}
        >
          {example.label}
        </button>
      ))}
    </div>
  );

  return (
    <section className="mock-bar" aria-label="モックのシナリオ">
      <span className="badge">モック</span>
      <span className="mock-note">
        例文を押すと入力欄に入ります（入力画面で使えます）
      </span>
      {renderGroup("状況の例", CATEGORY_EXAMPLE_LIST)}
      {renderGroup("動きの例", TAG_EXAMPLE_LIST)}
    </section>
  );
}
