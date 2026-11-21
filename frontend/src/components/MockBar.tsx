import {
  CATEGORY_EXAMPLE_LIST,
  TAG_EXAMPLE_LIST,
  type MockExample,
} from "../text/mockExamples.ts";
import { skillMixOf, skillMixText } from "../text/skills.ts";

/** モック表示の props。 */
interface MockBarProps {
  /** スキルごとの実装（GET /api/config の skills。無ければ null） */
  skills: Record<string, string> | null;
  /** 例文が選ばれたときに呼ぶ */
  onPick: (text: string) => void;
  /** 例文ボタンを使えるか（入力画面でのみ true） */
  canPick: boolean;
}

/**
 * 「モック」（一部だけなら「一部モック」）バッジと、シナリオを試せる例文ボタン。
 * 一部が実際のモデルのときは、どのスキルがどちらで動いているかも示す。
 * 開発者向けの表示なので、例文は折りたたんでおき、本来の画面を押し下げない。
 * @param props 例文選択の受け口
 */
export function MockBar({ skills, onPick, canPick }: MockBarProps) {
  const mix = skillMixOf(skills);
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
      <details>
        <summary>
          <span className="badge">{mix.partial ? "一部モック" : "モック"}</span>
          <span className="mock-note">例文を試す</span>
        </summary>
        {mix.partial && (
          <p className="mock-note mock-help">{skillMixText(mix)}</p>
        )}
        <p className="mock-note mock-help">
          例文を押すと入力欄に入ります（入力画面で使えます）
        </p>
        {renderGroup("状況の例", CATEGORY_EXAMPLE_LIST)}
        {renderGroup("動きの例", TAG_EXAMPLE_LIST)}
        {mix.partial && (
          <div className="mock-note mock-help">
            <p>タグは、モックで動いているスキルにだけ効きます</p>
            {mix.mock.includes("調査") && (
              <p>何を尋ねても、求人の掲載期限についての調査結果が返ります</p>
            )}
          </div>
        )}
      </details>
    </section>
  );
}
