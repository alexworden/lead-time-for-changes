package org.devmetrics.lt4c;

import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;

public class LeadTimeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(LeadTimeAnalyzer.class);
    private final GitHubClient githubClient;

    public LeadTimeAnalyzer(GitHubClient githubClient) {
        this.githubClient = githubClient;
    }

    public ReleaseAnalysis analyzeRelease(String releaseRef, String previousReleaseRef) throws Exception {
        logger.info("Analyzing release from {} to {}", previousReleaseRef, releaseRef);
        
        // Get tag dates from GitHub
        GHRef releaseTag = githubClient.getRepository().getRef("tags/" + releaseRef.replaceFirst("^refs/tags/", ""));
        GHRef previousReleaseTag = githubClient.getRepository().getRef("tags/" + previousReleaseRef.replaceFirst("^refs/tags/", ""));
        
        GHCommit releaseCommit = githubClient.getRepository().getCommit(releaseTag.getObject().getSha());
        GHCommit previousReleaseCommit = githubClient.getRepository().getCommit(previousReleaseTag.getObject().getSha());
        
        Date releaseDate = releaseCommit.getCommitDate();
        Date fromReleaseDate = previousReleaseCommit.getCommitDate();
        
        logger.debug("Release dates - from: {} to: {}", fromReleaseDate, releaseDate);
        
        List<PullRequest> pullRequests = githubClient.getPullRequestsBetweenTags(previousReleaseRef, releaseRef);
        logger.info("Found {} pull requests", pullRequests.size());

        // Set release date on each PR for lead time calculation
        for (PullRequest pr : pullRequests) {
            pr.setReleaseDate(releaseDate);
        }

        // Sort PRs by merge date
        pullRequests.sort(Comparator.comparing(PullRequest::getMergedAt));

        // Calculate lead times
        double[] leadTimes = pullRequests.stream()
            .mapToDouble(PullRequest::getLeadTimeHours)
            .toArray();

        double averageLeadTime = calculateAverage(leadTimes);
        double medianLeadTime = calculateMedian(leadTimes);
        double p90LeadTime = calculatePercentile(leadTimes, 90);

        logger.info("Lead time metrics - Average: {:.2f}h, Median: {:.2f}h, P90: {:.2f}h",
            averageLeadTime, medianLeadTime, p90LeadTime);

        return new ReleaseAnalysis(
            releaseRef,
            releaseTag.getObject().getSha(),
            releaseDate,
            previousReleaseRef,
            fromReleaseDate,
            pullRequests,
            averageLeadTime,
            medianLeadTime,
            p90LeadTime
        );
    }

    private double calculateAverage(double[] values) {
        if (values.length == 0) return 0.0;
        return Arrays.stream(values).average().orElse(0.0);
    }

    private double calculateMedian(double[] values) {
        if (values.length == 0) return 0.0;
        Arrays.sort(values);
        if (values.length % 2 == 0) {
            return (values[values.length / 2 - 1] + values[values.length / 2]) / 2.0;
        } else {
            return values[values.length / 2];
        }
    }

    private double calculatePercentile(double[] values, int percentile) {
        if (values.length == 0) return 0.0;
        Arrays.sort(values);
        int index = (int) Math.ceil(percentile / 100.0 * values.length) - 1;
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }
}
