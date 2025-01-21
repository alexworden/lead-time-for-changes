package org.devmetrics.lt4c;

import java.util.Date;

public class PullRequest {
    private final int number;
    private final String author;
    private final Date createdAt;
    private final Date mergedAt;
    private final String targetBranch;
    private final String mergeSha;
    private final String comment;

    public PullRequest(int number, String author, Date createdAt, Date mergedAt, String targetBranch, String mergeSha, String comment) {
        this.number = number;
        this.author = author;
        this.createdAt = createdAt;
        this.mergedAt = mergedAt;
        this.targetBranch = targetBranch;
        this.mergeSha = mergeSha;
        this.comment = comment;
    }

    public int getNumber() {
        return number;
    }

    public String getAuthor() {
        return author;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getMergedAt() {
        return mergedAt;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getMergeSha() {
        return mergeSha;
    }

    public String getComment() {
        return comment;
    }

    public double getLeadTimeHours() {
        if (createdAt == null || mergedAt == null) {
            return 0.0;
        }
        long diffInMillis = mergedAt.getTime() - createdAt.getTime();
        return diffInMillis / (1000.0 * 60 * 60); // Convert milliseconds to hours
    }
}
