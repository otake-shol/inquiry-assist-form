package com.example.jobboard;

import java.time.Duration;
import java.time.Instant;

/** 求人の掲載状態を扱うサービス（サンプル）。 */
public class JobPostingService {

    /** 掲載期限（expiresAt）を過ぎた求人は、応募者向けの一覧に出さない。 */
    public boolean isExpired(JobPosting posting, Instant now) {
        // 期限ちょうどの時刻は、期限切れとして扱う
        return !now.isBefore(posting.expiresAt());
    }

    /** 掲載期間の延長は、掲載中の求人にだけ認める。 */
    public JobPosting extend(JobPosting posting, Duration extra, Instant now) {
        if (isExpired(posting, now)) {
            throw new IllegalStateException("期限切れの求人は延長できない");
        }
        return new JobPosting(
                posting.id(), posting.title(), posting.status(), posting.expiresAt().plus(extra));
    }
}
