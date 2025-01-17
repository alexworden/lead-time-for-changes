package org.devmetrics.lt4c;

import java.util.Date;
import java.util.List;

/**
 * Data structure containing the results of analyzing a release
 */
public class ReleaseAnalysis {
    private final String releaseTag;
    private final String releaseCommit;
    private final Date releaseDate;
    private final List<PullRequest> pullRequests;
    private final double averageLeadTimeHours;
    private final double medianLeadTimeHours;
    private final double p90LeadTimeHours;

    public ReleaseAnalysis(String releaseTag, String releaseCommit, Date releaseDate, 
                          List<PullRequest> pullRequests, double averageLeadTimeHours, 
                          double medianLeadTimeHours, double p90LeadTimeHours) {
        this.releaseTag = releaseTag;
        this.releaseCommit = releaseCommit;
        this.releaseDate = releaseDate;
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
}
