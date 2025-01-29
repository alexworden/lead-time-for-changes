package org.devmetrics.lt4c;

import java.text.SimpleDateFormat;
import java.util.Date;

public class PullRequest {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
    private final int number;
    private final String author;
    private Date createdAt;
    private final Date mergedAt;
    private final String targetBranch;
    private final String mergeSha;
    private final String comment;
    private Date releaseDate;
    private int linesAdded;
    private int linesDeleted;
    private int linesModified;

    public PullRequest(int number, String author, Date createdAt, Date mergedAt, String targetBranch, String mergeSha, String comment) {
        this.number = number;
        this.author = author;
        this.createdAt = createdAt;
        this.mergedAt = mergedAt;
        this.targetBranch = targetBranch;
        this.mergeSha = mergeSha;
        this.comment = comment;
        this.linesAdded = 0;
        this.linesDeleted = 0;
        this.linesModified = 0;
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

    public void setReleaseDate(Date releaseDate) {
        this.releaseDate = releaseDate;
    }

    public Date getReleaseDate() {
        return releaseDate;
    }

    public void setLineChanges(int added, int deleted, int modified) {
        this.linesAdded = added;
        this.linesDeleted = deleted;
        this.linesModified = modified;
    }

    public int getLinesAdded() {
        return linesAdded;
    }

    public int getLinesDeleted() {
        return linesDeleted;
    }

    public int getLinesModified() {
        return linesModified;
    }

    public int getTotalLinesChanged() {
        return linesAdded + linesDeleted + linesModified;
    }

    public double getLeadTimeHours() {
        if (mergedAt == null || releaseDate == null) {
            return 0.0;
        }
        long diffInMillis = releaseDate.getTime() - mergedAt.getTime();
        return diffInMillis / (1000.0 * 60 * 60); // Convert milliseconds to hours
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("PR #%d by %s\n", number, author));
        sb.append(String.format("  Comment: %s", comment.split("\n")[0]));
        sb.append(String.format("  Merged at: %s\n", DATE_FORMAT.format(mergedAt)));
        sb.append(String.format("  Lead Time: %.2f hours\n", getLeadTimeHours()));
        sb.append(String.format("  Target Branch: %s\n", targetBranch));
        sb.append(String.format("  Commit: %s\n", mergeSha));
        sb.append(String.format("  Changes: +" + linesAdded + " -" + linesDeleted + 
                                 " ~" + linesModified + " lines\n"));
        return sb.toString();
    }
}
