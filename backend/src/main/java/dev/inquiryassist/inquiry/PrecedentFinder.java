package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Placement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 同じ置き場所の先例を引く（契約書「同じ置き場所の先例」）。担当者向けの詳細の {@code precedents} と、助言に渡す先例の両方が、
 * ここを通る。先例を探すのはコードで、似ているかどうかの判断は、助言に任せる。
 *
 * <p>同じ置き場所とは、システムと種類が同じこと。問い合わせのシステムか種類が決まっていなければ、空。並びは、機能が同じ先例を先に、
 * それ以外を後に。その中は新しい順。最大5件。その問い合わせ自身から生まれた先例は含めない。確かめられていない先例も含める。
 * 言語は問わない（書かれた言語のまま返す）。
 */
@Component
class PrecedentFinder {

    /** 引く先例の数の上限。 */
    static final int LIMIT = 5;

    /** 機能が違う先例も含めるため、並べ替える前に引く数。機能が同じものを先に出すには、上限より多く引く必要がある。 */
    private static final int CANDIDATES = 200;

    private final PrecedentRepository precedents;

    /**
     * @param precedents 先例の保存先
     */
    PrecedentFinder(PrecedentRepository precedents) {
        this.precedents = precedents;
    }

    /**
     * 問い合わせの置き場所と同じ置き場所の先例を返す。
     *
     * @param inquiry 問い合わせ記録
     * @return 先例。機能が同じものが先、その中は新しい順。最大5件。システムか種類が決まっていなければ空
     */
    List<PrecedentEntity> find(InquiryEntity inquiry) {
        return find(inquiry.id, inquiry.placement());
    }

    /**
     * 置き場所と同じ置き場所の先例を返す。
     *
     * @param inquiryId 除く問い合わせ（その問い合わせ自身から生まれた先例）
     * @param placement 置き場所
     * @return 先例。機能が同じものが先、その中は新しい順。最大5件。システムか種類が決まっていなければ空
     */
    List<PrecedentEntity> find(UUID inquiryId, Placement placement) {
        if (placement.systemId() == null || placement.type() == null) {
            return List.of();
        }
        List<PrecedentEntity> samePlace =
                precedents.findSamePlace(placement.systemId(), placement.type(), inquiryId, PageRequest.of(0, CANDIDATES));
        // 並べ替えは安定（新しい順を保つ）。機能が同じ（どちらも決まっていない場合を含む）ものを先にする
        return samePlace.stream()
                .sorted((a, b) -> Boolean.compare(
                        !Objects.equals(a.functionId, placement.functionId()),
                        !Objects.equals(b.functionId, placement.functionId())))
                .limit(LIMIT)
                .toList();
    }
}
