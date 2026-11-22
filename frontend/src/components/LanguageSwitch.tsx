import { useI18n } from "../i18n/context.ts";
import { MESSAGES } from "../i18n/messages.ts";

/**
 * 言語の切り替え。画面の上部（本人の画面も担当者の画面も）に置く。選択肢は、その言語自身の名前
 * （「日本語」「English」）で書き、いま選んでいる言語に `aria-current` を付ける。
 * 使える言語が1つのとき（または API の言語が分かっていないとき）は何も出さない。
 */
export function LanguageSwitch() {
  const { language, languages, canSwitch, setLanguage, messages } = useI18n();
  if (!canSwitch) return null;
  return (
    <div
      className="lang-switch"
      role="group"
      aria-label={messages.text.languageLabel}
    >
      {languages.map((candidate) => (
        <button
          key={candidate}
          type="button"
          lang={candidate}
          aria-current={candidate === language ? "true" : undefined}
          onClick={() => setLanguage(candidate)}
        >
          {MESSAGES[candidate].meta.name}
        </button>
      ))}
    </div>
  );
}
