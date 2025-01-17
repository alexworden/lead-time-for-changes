package org.devmetrics.lt4c;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LeadTimeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(LeadTimeAnalyzer.class);
    private static final Pattern PR_MERGE_PATTERN = Pattern.compile("Merge pull request #(\\d+) from (.+)");
    private static final Pattern PR_SQUASH_PATTERN = Pattern.compile("\\(#(\\d+)\\)$");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

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
            logger.error("Failed to resolve commits. Release commit: {}, Previous release commit: {}", 
                releaseCommit != null ? releaseCommit.getName() : "null",
                previousReleaseCommit != null ? previousReleaseCommit.getName() : "null");
            throw new IllegalArgumentException("Could not resolve release references");
        }

        logger.info("Successfully resolved commits - Release: {}, Previous: {}", 
            releaseCommit.getName(), previousReleaseCommit.getName());

        List<PullRequest> pullRequests;
        if (githubClient != null) {
            // Use GitHub API to get PRs
            logger.info("Using GitHub API to find pull requests");
            pullRequests = githubClient.getPullRequestsBetweenTags(previousReleaseRef, releaseRef);
            logger.info("GitHub API returned {} pull requests", pullRequests.size());
        } else {
            // Fallback to git log analysis
            logger.info("No GitHub client available, falling back to git log analysis");
            pullRequests = new ArrayList<>();
            LogCommand logCommand = git.log()
                    .addRange(previousReleaseCommit, releaseCommit);

            for (RevCommit commit : logCommand.call()) {
                String message = commit.getFullMessage().trim();
                Matcher mergeMatcher = PR_MERGE_PATTERN.matcher(message);
                Matcher squashMatcher = PR_SQUASH_PATTERN.matcher(message);

                if (mergeMatcher.find() || squashMatcher.find()) {
                    int prNumber;
                    String targetBranch = "";
                    if (mergeMatcher.find(0)) {
                        prNumber = Integer.parseInt(mergeMatcher.group(1));
                        targetBranch = mergeMatcher.group(2);
                    } else {
                        prNumber = Integer.parseInt(squashMatcher.group(1));
                    }

                    Date commitDate = commit.getAuthorIdent().getWhen();
                    PullRequest pr = new PullRequest(
                            prNumber,
                            commit.getAuthorIdent().getName(),
                            commitDate,
                            commitDate,
                            targetBranch,
                            commit.getName(),
                            message
                    );
                    pullRequests.add(pr);
                }
            }
        }

        // Sort PRs by merge date
        pullRequests.sort(Comparator.comparing(PullRequest::getMergedAt));

        // Get release date
        Date releaseDate;
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(releaseCommit);
            releaseDate = commit.getCommitterIdent().getWhen();
        }

        // Calculate lead times
        double[] leadTimes = pullRequests.stream()
            .mapToDouble(pr -> {
                long diffInMillis = releaseDate.getTime() - pr.getMergedAt().getTime();
                return diffInMillis / (1000.0 * 60 * 60); // Convert milliseconds to hours
            })
            .toArray();

        double averageLeadTime = calculateAverage(leadTimes);
        double medianLeadTime = calculateMedian(leadTimes);
        double p90LeadTime = calculatePercentile(leadTimes, 90);

        return new ReleaseAnalysis(
            releaseRef,
            releaseCommit.getName(),
            releaseDate,
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
