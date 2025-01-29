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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Release Analysis for %s (commit: %s)\n", releaseTag, releaseCommit));
        sb.append("\nPull Requests:\n");
        for (PullRequest pr : pullRequests) {
            sb.append(pr.toString()).append("\n");
        }

        sb.append("\nSummary:\n");
        sb.append("==========\n");
        sb.append(String.format("- Release %s was made on %s\n", releaseTag, DATE_FORMAT.format(releaseDate)));
        sb.append(String.format("- Included %d pull requests\n", pullRequests.size()));
        sb.append(String.format("- Lead Time Metrics:\n"));
        sb.append(String.format("  * Average: %.1f hours (%.1f days)\n", averageLeadTimeHours, averageLeadTimeHours/24));
        sb.append(String.format("  * Median: %.1f hours (%.1f days)\n", medianLeadTimeHours, medianLeadTimeHours/24));
        sb.append(String.format("  * 90th percentile: %.1f hours (%.1f days)\n", p90LeadTimeHours, p90LeadTimeHours/24));
        
        // Add distribution summary
        int fastPRs = 0; // < 24 hours
        int mediumPRs = 0; // 24-72 hours
        int slowPRs = 0; // > 72 hours
        
        for (PullRequest pr : pullRequests) {
            double leadTime = pr.getLeadTimeHours();
            if (leadTime < 24) {
                fastPRs++;
            } else if (leadTime < 72) {
                mediumPRs++;
            } else {
                slowPRs++;
            }
        }
        
        sb.append("\n- Lead Time Distribution:\n");
        sb.append(String.format("  * Fast (< 24 hours): %d PRs (%.1f%%)\n", 
            fastPRs, (fastPRs * 100.0 / pullRequests.size())));
        sb.append(String.format("  * Medium (24-72 hours): %d PRs (%.1f%%)\n", 
            mediumPRs, (mediumPRs * 100.0 / pullRequests.size())));
        sb.append(String.format("  * Slow (> 72 hours): %d PRs (%.1f%%)\n", 
            slowPRs, (slowPRs * 100.0 / pullRequests.size())));

        return sb.toString();
    }
}
