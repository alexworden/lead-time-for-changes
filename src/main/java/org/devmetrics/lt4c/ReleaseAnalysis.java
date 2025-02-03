package org.devmetrics.lt4c;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Data structure containing the results of analyzing a release
 */
public class ReleaseAnalysis {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
    private final String releaseTag;
    private final String releaseCommit;
    private final Date releaseDate;
    private final String fromReleaseTag;
    private final Date fromReleaseDate;
    private final List<PullRequest> pullRequests;
    private final double averageLeadTimeHours;
    private final double medianLeadTimeHours;
    private final double p90LeadTimeHours;

    public ReleaseAnalysis(String releaseTag, String releaseCommit, Date releaseDate, 
                          String fromReleaseTag, Date fromReleaseDate,
                          List<PullRequest> pullRequests, double averageLeadTimeHours, 
                          double medianLeadTimeHours, double p90LeadTimeHours) {
        this.releaseTag = releaseTag;
        this.releaseCommit = releaseCommit;
        this.releaseDate = releaseDate;
        this.fromReleaseTag = fromReleaseTag;
        this.fromReleaseDate = fromReleaseDate;
        this.pullRequests = pullRequests;
        this.averageLeadTimeHours = averageLeadTimeHours;
        this.medianLeadTimeHours = medianLeadTimeHours;
        this.p90LeadTimeHours = p90LeadTimeHours;
    }

    public String getReleaseTag() {
        return releaseTag;
    }

    public String getReleaseCommit() {
        return releaseCommit;
    }

    public Date getReleaseDate() {
        return releaseDate;
    }

    public String getFromReleaseTag() {
        return fromReleaseTag;
    }

    public Date getFromReleaseDate() {
        return fromReleaseDate;
    }

    public List<PullRequest> getPullRequests() {
        return pullRequests;
    }

    public double getAverageLeadTimeHours() {
        return averageLeadTimeHours;
    }

    public double getMedianLeadTimeHours() {
        return medianLeadTimeHours;
    }

    public double getP90LeadTimeHours() {
        return p90LeadTimeHours;
    }

    public int getTotalPullRequests() {
        return pullRequests.size();
    }

    public int getTotalLinesAdded() {
        return pullRequests.stream()
            .mapToInt(PullRequest::getAdditions)
            .sum();
    }

    public int getTotalLinesDeleted() {
        return pullRequests.stream()
            .mapToInt(PullRequest::getDeletions)
            .sum();
    }

    public int getTotalLinesChanged() {
        return pullRequests.stream()
            .mapToInt(PullRequest::getTotalChanges)
            .sum();
    }

    public double getAverageLinesChanged() {
        if (pullRequests.isEmpty()) {
            return 0.0;
        }
        return getTotalLinesChanged() / (double) pullRequests.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Release Analysis for %s%n", releaseTag));
        sb.append(String.format("From: %s%n", fromReleaseTag));
        sb.append(String.format("Number of PRs: %d%n", getTotalPullRequests()));
        sb.append(String.format("Average Lead Time: %.2f hours%n", averageLeadTimeHours));
        sb.append(String.format("Median Lead Time: %.2f hours%n", medianLeadTimeHours));
        sb.append(String.format("90th Percentile Lead Time: %.2f hours%n", p90LeadTimeHours));
        sb.append(String.format("Total Lines Changed: %d (+%d -%d)%n", 
            getTotalLinesChanged(), getTotalLinesAdded(), getTotalLinesDeleted()));
        sb.append(String.format("Average Lines per PR: %.2f%n", getAverageLinesChanged()));
        return sb.toString();
    }
}
