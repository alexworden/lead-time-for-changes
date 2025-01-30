package org.devmetrics.lt4c;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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
            logger.debug("Fetching tags...");
            git.fetch().setRefSpecs("+refs/tags/*:refs/tags/*").call();
            logger.debug("Tags fetched successfully");
        } catch (org.eclipse.jgit.api.errors.InvalidRemoteException e) {
            logger.warn("No remote repository found, skipping tag fetch");
        } catch (org.eclipse.jgit.api.errors.TransportException e) {
            logger.warn("Unable to fetch from remote repository (authentication or connection issue), skipping tag fetch: {}", e.getMessage());
        }
        
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId releaseId = repository.resolve(releaseRef);
            ObjectId previousReleaseId = repository.resolve(previousReleaseRef);

            if (releaseId == null || previousReleaseId == null) {
                throw new IllegalArgumentException(String.format(
                    "Could not resolve release refs to commits. Release '%s' resolved to: %s, Previous '%s' resolved to: %s",
                    releaseRef, releaseId, previousReleaseRef, previousReleaseId));
            }

            // Peel tags to get their underlying commits
            RevObject releaseObj = walk.parseAny(releaseId);
            while (releaseObj instanceof RevTag) {
                releaseObj = walk.peel(releaseObj);
            }
            
            RevObject previousReleaseObj = walk.parseAny(previousReleaseId);
            while (previousReleaseObj instanceof RevTag) {
                previousReleaseObj = walk.peel(previousReleaseObj);
            }

            if (!(releaseObj instanceof RevCommit) || !(previousReleaseObj instanceof RevCommit)) {
                throw new IllegalArgumentException(String.format(
                    "Could not resolve refs to commits. Release '%s' resolved to %s, Previous '%s' resolved to %s",
                    releaseRef, releaseObj.getClass().getSimpleName(),
                    previousReleaseRef, previousReleaseObj.getClass().getSimpleName()));
            }

            RevCommit releaseCommit = (RevCommit) releaseObj;
            RevCommit previousReleaseCommit = (RevCommit) previousReleaseObj;

            logger.info("Resolved release '{}' to commit: {}", releaseRef, releaseCommit.getName());
            logger.info("Resolved previous release '{}' to commit: {}", previousReleaseRef, previousReleaseCommit.getName());

            Date releaseDate = null;
            if (releaseCommit != null) {
                releaseDate = releaseCommit.getAuthorIdent().getWhen();
            }

            Date fromReleaseDate = null;
            if (previousReleaseCommit != null) {
                fromReleaseDate = previousReleaseCommit.getAuthorIdent().getWhen();
            }

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
