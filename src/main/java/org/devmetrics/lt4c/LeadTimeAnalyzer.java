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

public class LeadTimeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(LeadTimeAnalyzer.class);
    private static final Pattern PR_MERGE_PATTERN = Pattern.compile("Merge pull request #(\\d+) from (.+)");
    private static final Pattern PR_SQUASH_PATTERN = Pattern.compile("\\(#(\\d+)\\)$");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

    private final Repository repository;
    private final Git git;

    public LeadTimeAnalyzer(File repoDir) throws Exception {
        repository = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
        git = new Git(repository);
    }

    public void analyzeRelease(String releaseRef, String previousReleaseRef) throws Exception {
        // Fetch tags first
        logger.info("Fetching tags...");
        git.fetch().setRefSpecs("+refs/tags/*:refs/tags/*").call();
        logger.info("Tags fetched successfully");

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

        // Get all commits between releases
        LogCommand logCommand = git.log()
                .addRange(previousReleaseCommit, releaseCommit);

        List<PullRequest> pullRequests = new ArrayList<>();
        Map<String, RevCommit> prCommits = new HashMap<>();

        try (RevWalk revWalk = new RevWalk(repository)) {
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

                    PullRequest pr = new PullRequest(
                            prNumber,
                            commit.getAuthorIdent().getName(),
                            commit.getAuthorIdent().getWhen(),
                            targetBranch,
                            commit.getName(),
                            message
                    );
                    pullRequests.add(pr);
                    prCommits.put(commit.getName(), commit);
                }
            }
        }

        // Sort PRs by merge date
        pullRequests.sort(Comparator.comparing(PullRequest::getMergeDate));

        // Get release date
        Date releaseDate;
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(releaseCommit);
            releaseDate = commit.getCommitterIdent().getWhen();
        }

        // Print analysis
        System.out.println("\nAnalysis Results:");
        System.out.println("=================");
        System.out.printf("Release Tag: %s%n", releaseRef);
        System.out.printf("Release Commit: %s%n", releaseCommit.getName());
        System.out.printf("Release Date: %s%n", DATE_FORMAT.format(releaseDate));
        System.out.println();

        // Calculate lead times
        for (PullRequest pr : pullRequests) {
            long diffInMillis = releaseDate.getTime() - pr.getMergeDate().getTime();
            double leadTimeHours = diffInMillis / (1000.0 * 60 * 60); // Convert milliseconds to hours
            pr.setLeadTimeHours(leadTimeHours);
        }

        System.out.println("Number of Pull Requests: " + pullRequests.size());
        
        if (!pullRequests.isEmpty()) {
            // Calculate statistics
            DoubleSummaryStatistics leadTimeStats = pullRequests.stream()
                    .mapToDouble(PullRequest::getLeadTimeHours)
                    .summaryStatistics();

            System.out.printf("Lead Time Statistics (hours):%n");
            System.out.printf("  Average: %.2f%n", leadTimeStats.getAverage());
            System.out.printf("  Min: %.2f%n", leadTimeStats.getMin());
            System.out.printf("  Max: %.2f%n", leadTimeStats.getMax());
            System.out.printf("  Median: %.2f%n", calculateMedian(pullRequests.stream()
                    .mapToDouble(PullRequest::getLeadTimeHours)
                    .sorted()
                    .toArray()));

            // Print individual PR details
            System.out.println("\nPull Request Details:");
            System.out.println("=====================");
            for (PullRequest pr : pullRequests) {
                System.out.printf("PR #%d by %s%n", pr.getNumber(), pr.getAuthor());
                System.out.printf("  Merged at: %s%n", DATE_FORMAT.format(pr.getMergeDate()));
                System.out.printf("  Lead Time: %.2f hours%n", pr.getLeadTimeHours());
                System.out.printf("  Target Branch: %s%n", pr.getTargetBranch());
                System.out.printf("  Commit: %s%n", pr.getMergeSha());
                System.out.printf("  Comment: %s%n", pr.getComment());
                System.out.println();
            }
        }
    }

    private double calculateMedian(double[] values) {
        if (values.length == 0) return 0;
        
        int middle = values.length / 2;
        if (values.length % 2 == 1) {
            return values[middle];
        } else {
            return (values[middle-1] + values[middle]) / 2.0;
        }
    }
}
