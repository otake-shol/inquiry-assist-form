package dev.inquiryassist.core.intake;

import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Urgency;
import java.util.List;

/**
 * 入口の質問を決めるのに使う設定。名前は、呼び出し側が既定の言語に解決したものを渡す。
 *
 * @param types 種類の名前（聞く順）
 * @param urgencies 緊急度の名前
 * @param systems システムと機能の一覧
 * @param cells マスの設定。書かれた順
 */
public record IntakeCatalog(
        List<TypeInfo> types, List<UrgencyInfo> urgencies, List<SystemInfo> systems, List<Cell> cells) {

    /**
     * 種類の名前。
     *
     * @param type 種類
     * @param label 画面に出す名前
     * @param hint 名前を補う短い説明
     */
    public record TypeInfo(InquiryType type, String label, String hint) {}

    /**
     * 緊急度の名前。
     *
     * @param urgency 緊急度
     * @param label 画面に出す名前
     */
    public record UrgencyInfo(Urgency urgency, String label) {}

    /**
     * システムと、その機能。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     * @param functions 機能。設定がなければ空
     */
    public record SystemInfo(String id, String label, List<Choice> functions) {

        public SystemInfo {
            functions = functions == null ? List.of() : List.copyOf(functions);
        }
    }

    /**
     * マスの設定1件。指定した項目（{@code null} でないもの）がすべて当てはまるときに使う。
     *
     * @param system システムの識別子。{@code null} なら、すべてのシステム
     * @param function 機能の識別子。{@code null} なら、すべての機能
     * @param type 種類。{@code null} なら、すべての種類
     * @param questions 詳しい質問。空なら設定に質問はない
     */
    public record Cell(String system, String function, InquiryType type, List<IntakeQuestion> questions) {

        public Cell {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        /**
         * 指定した項目の数。多いほど具体的な設定として優先する。
         *
         * @return 指定した項目の数
         */
        public int specificity() {
            return (system != null ? 1 : 0) + (function != null ? 1 : 0) + (type != null ? 1 : 0);
        }
    }

    public IntakeCatalog {
        types = types == null ? List.of() : List.copyOf(types);
        urgencies = urgencies == null ? List.of() : List.copyOf(urgencies);
        systems = systems == null ? List.of() : List.copyOf(systems);
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    /**
     * @param id システムの識別子
     * @return そのシステム。なければ {@code null}
     */
    public SystemInfo system(String id) {
        return systems.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * 受付に渡す候補にする。
     *
     * @return システムと機能の候補
     */
    public List<SystemOption> candidates() {
        return systems.stream()
                .map(s -> new SystemOption(
                        s.id(),
                        s.label(),
                        s.functions().stream()
                                .map(f -> new FunctionOption(f.id(), f.label()))
                                .toList()))
                .toList();
    }

    /**
     * @param type 種類
     * @return 種類の名前。設定がなければ識別子
     */
    public String typeLabel(InquiryType type) {
        return types.stream()
                .filter(t -> t.type() == type)
                .map(TypeInfo::label)
                .findFirst()
                .orElse(type.name());
    }

    /**
     * @param urgency 緊急度
     * @return 緊急度の名前。設定がなければ識別子
     */
    public String urgencyLabel(Urgency urgency) {
        return urgencies.stream()
                .filter(u -> u.urgency() == urgency)
                .map(UrgencyInfo::label)
                .findFirst()
                .orElse(urgency.name());
    }

    /**
     * @param systemId システムの識別子
     * @return システムの名前。設定になければ識別子（設定が変わった後の記録を読めるように）
     */
    public String systemLabel(String systemId) {
        SystemInfo system = system(systemId);
        return system == null ? systemId : system.label();
    }

    /**
     * @param systemId システムの識別子
     * @param functionId 機能の識別子
     * @return 機能の名前。設定になければ識別子
     */
    public String functionLabel(String systemId, String functionId) {
        SystemInfo system = system(systemId);
        if (system == null) {
            return functionId;
        }
        return system.functions().stream()
                .filter(f -> f.id().equals(functionId))
                .map(Choice::label)
                .findFirst()
                .orElse(functionId);
    }
}
