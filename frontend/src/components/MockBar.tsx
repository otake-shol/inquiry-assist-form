import { useMessages } from "../i18n/context.ts";
import {
  tagExampleList,
  typeExampleList,
  type MockExample,
} from "../text/mockExamples.ts";
import { skillMixOf, skillMixText } from "../text/skills.ts";

/** モック表示の props。 */
interface MockBarProps {
  /** スキルごとの実装（GET /api/config の skills。無ければ null） */
  skills: Record<string, string> | null;
  /** 例文が選ばれたときに呼ぶ */
  onPick: (text: string) => void;
  /** 例文ボタンを使えるか（問い合わせの作成前だけ true） */
  canPick: boolean;
}

/**
 * 「モック」（一部だけなら「一部モック」）バッジと、シナリオを試せる例文ボタン。
 * すべてのスキルがモックのときは、「すべてのスキルが、見本の実装で動いています」の1行だけを示す。
 * モック以外が混ざっているときだけ、どのスキルがどちらで動いているかの内訳を示す。
 * 開発者向けの表示なので、例文は折りたたんでおき、本来の画面を押し下げない。
 * @param props 例文選択の受け口
 */
export function MockBar({ skills, onPick, canPick }: MockBarProps) {
  const messages = useMessages();
  const { mock } = messages;
  const mix = skillMixOf(skills, messages.skills.labels);
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
    <section className="mock-bar" aria-label={mock.barLabel}>
      <details>
        <summary data-open-label={mock.open} data-close-label={mock.close}>
          <span className="badge">
            {mix.partial ? mock.badgePartial : mock.badge}
          </span>
          <span className="mock-note">{mock.tryExamples}</span>
        </summary>
        <p className="mock-note mock-help">
          {mix.partial
            ? skillMixText(mix, messages.skills)
            : messages.skills.allMock}
        </p>
        <p className="mock-note mock-help">{mock.pickHelp}</p>
        {renderGroup(mock.situations, typeExampleList(mock))}
        {renderGroup(mock.actions, tagExampleList(mock))}
        {mix.partial && (
          <div className="mock-note mock-help">
            <p>{mock.tagHelp}</p>
            {mix.mockIds.includes("investigator") && (
              <p>{mock.investigationHelp}</p>
            )}
          </div>
        )}
      </details>
    </section>
  );
}
