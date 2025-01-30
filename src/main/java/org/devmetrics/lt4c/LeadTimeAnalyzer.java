package org.devmetrics.lt4c;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class LeadTimeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(LeadTimeAnalyzer.class);

    private final Repository repository;
    private final Git git;
    private GitHubClient githubClient;

    public LeadTimeAnalyzer(File repoDir) throws Exception {
        repository = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
        git = new Git(repository);
    }

    public void setGitHubClient(GitHubClient client) {
        this.githubClient = client;
    }

    public ReleaseAnalysis analyzeRelease(String releaseRef, String previousReleaseRef) throws Exception {
        // Only fetch tags if we have a remote repository and can authenticate
        try {
            logger.info("Fetching tags...");
            git.fetch().setRefSpecs("+refs/tags/*:refs/tags/*").call();
            logger.info("Tags fetched successfully");
        } catch (org.eclipse.jgit.api.errors.InvalidRemoteException e) {
            logger.debug("No remote repository found, skipping tag fetch");
        } catch (org.eclipse.jgit.api.errors.TransportException e) {
            logger.debug("Unable to fetch from remote repository (authentication or connection issue), skipping tag fetch: {}", e.getMessage());
        }

        // Get release commit info
        logger.info("Resolving release references: {} and {}", releaseRef, previousReleaseRef);
        ObjectId releaseCommit = repository.resolve(releaseRef + "^{commit}");
        ObjectId previousReleaseCommit = repository.resolve(previousReleaseRef + "^{commit}");

        if (releaseCommit == null || previousReleaseCommit == null) {
            throw new IllegalArgumentException(String.format(
                "Could not resolve release refs to commits. Release '%s' resolved to: %s, Previous '%s' resolved to: %s",
                releaseRef, releaseCommit, previousReleaseRef, previousReleaseCommit));
        }

        logger.debug("Resolved release '{}' to commit: {}", releaseRef, releaseCommit.getName());
        logger.debug("Resolved previous release '{}' to commit: {}", previousReleaseRef, previousReleaseCommit.getName());

        Date releaseDate = null;
        RevCommit releaseCommitObj = git.log().add(releaseCommit).setMaxCount(1).call().iterator().next();
        if (releaseCommitObj != null) {
            releaseDate = releaseCommitObj.getAuthorIdent().getWhen();
        }

        Date fromReleaseDate = null;
        RevCommit fromReleaseCommitObj = git.log().add(previousReleaseCommit).setMaxCount(1).call().iterator().next();
        if (fromReleaseCommitObj != null) {
            fromReleaseDate = fromReleaseCommitObj.getAuthorIdent().getWhen();
        }

        logger.info("Successfully resolved commits between Previous {} and Release: {}",
            previousReleaseCommit.getName(), releaseCommit.getName());

        List<PullRequest> pullRequests = githubClient.getPullRequestsBetweenTags(previousReleaseRef, releaseRef);

        // Set release date on each PR
        for (PullRequest pr : pullRequests) {
            pr.setReleaseDate(releaseDate);
        }

        // Sort PRs by merge date
        pullRequests.sort(Comparator.comparing(PullRequest::getMergedAt));

        // Calculate lead times
        double[] leadTimes = pullRequests.stream().mapToDouble(PullRequest::getLeadTimeHours).toArray();

        double averageLeadTime = calculateAverage(leadTimes);
        double medianLeadTime = calculateMedian(leadTimes);
        double p90LeadTime = calculatePercentile(leadTimes, 90);

        return new ReleaseAnalysis(
            releaseRef,
            releaseCommit.getName(),
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
