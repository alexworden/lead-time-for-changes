package org.devmetrics.lt4c;

import java.util.Date;

public class PullRequest {
    private final int number;
    private final String title;
    private final String author;
    private final String destinationBranch;
    private final String mergeCommit;
    private final Date createdAt;
    private final Date mergedAt;
    private final int additions;
    private final int deletions;
    private Date releaseDate;  // When this PR was included in a release
    private final String body; // Description of the PR

    public PullRequest(int number, String title, String author, String destinationBranch,
                      String mergeCommit, Date createdAt, Date mergedAt, 
                      int additions, int deletions, String body) {
        this.number = number;
        this.title = title;
        this.author = author;
        this.destinationBranch = destinationBranch;
        this.mergeCommit = mergeCommit;
        this.createdAt = createdAt;
        this.mergedAt = mergedAt;
        this.additions = additions;
        this.deletions = deletions;
        this.body = body;
    }

    public int getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getDestinationBranch() {
        return destinationBranch;
    }

    public String getMergeCommit() {
        return mergeCommit;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getMergedAt() {
        return mergedAt;
    }

    public int getAdditions() {
        return additions;
    }

    public int getDeletions() {
        return deletions;
    }

    public int getTotalChanges() {
        return additions + deletions;
    }

    public void setReleaseDate(Date releaseDate) {
        this.releaseDate = releaseDate;
    }

    public Date getReleaseDate() {
        return releaseDate;
    }

    public double getLeadTimeHours() {
        if (mergedAt == null || releaseDate == null) {
            return 0.0;
        }
        long diffInMillis = releaseDate.getTime() - mergedAt.getTime();
        return diffInMillis / (1000.0 * 60 * 60); // Convert milliseconds to hours
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PullRequest that = (PullRequest) o;
        return number == that.number;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(number);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("PR #%d: %s%n", number, title));
        sb.append(String.format("  Author: %s%n", author));
        sb.append(String.format("  Target Branch: %s%n", destinationBranch));
        sb.append(String.format("  Created: %s%n", createdAt));
        sb.append(String.format("  Merged: %s%n", mergedAt));
        
        if (body != null && !body.isEmpty()) {
            String firstLine = body.split("\\r?\\n")[0].trim();
            sb.append(String.format("  Description: %s%n", firstLine));
        }
        
        sb.append(String.format("  Changes: +%d -%d lines (total: %d)%n", additions, deletions, getTotalChanges()));
        sb.append(String.format("  Lead Time: %.1f hours", getLeadTimeHours()));
        return sb.toString();
    }
}
