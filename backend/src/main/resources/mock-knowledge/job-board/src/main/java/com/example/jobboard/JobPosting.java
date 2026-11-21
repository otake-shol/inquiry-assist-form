package com.example.jobboard;

import java.time.Instant;

/** 求人1件。掲載期限（expiresAt）を過ぎると応募者向けの一覧に出なくなる。 */
public record JobPosting(String id, String title, PostingStatus status, Instant expiresAt) {

    /** 求人の掲載状態。 */
    public enum PostingStatus {
        DRAFT,
        PUBLISHED,
        CLOSED
    }
}
