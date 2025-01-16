package org.devmetrics;

import java.util.Date;

public class PullRequest {
    private final int number;
    private final String author;
    private final Date mergeDate;
    private final String targetBranch;
    private final String mergeSha;
    private final String comment;
    private double leadTimeHours;

    public PullRequest(int number, String author, Date mergeDate, String targetBranch, String mergeSha, String comment) {
        this.number = number;
        this.author = author;
        this.mergeDate = mergeDate;
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

    public Date getMergeDate() {
        return mergeDate;
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
        return leadTimeHours;
    }

    public void setLeadTimeHours(double leadTimeHours) {
        this.leadTimeHours = leadTimeHours;
    }
}
