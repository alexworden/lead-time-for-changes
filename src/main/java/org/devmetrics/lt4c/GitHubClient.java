package org.devmetrics.lt4c;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubClient {
    private static final Logger logger = LoggerFactory.getLogger(GitHubClient.class);
    private final GitHub github;
    private final GHRepository repository;
    private final Repository gitRepo;
    private final Git git;

    public GitHubClient(String token, String repoUrl, File repoDir) throws IOException {
        // Parse the GitHub host from the URL
        String githubHost;
        String repoPath;
        
        if (repoUrl.startsWith("https://")) {
            // Handle HTTPS URLs
            String[] parts = repoUrl.substring(8).split("/", 2);
            githubHost = parts[0];
            repoPath = parts[1];
        } else if (repoUrl.startsWith("git@")) {
            // Handle SSH URLs
            String[] parts = repoUrl.substring(4).split(":", 2);
            githubHost = parts[0];
            repoPath = parts[1];
        } else {
            throw new IllegalArgumentException("Invalid GitHub URL format: " + repoUrl);
        }
        
        // Remove .git suffix if present
        if (repoPath.endsWith(".git")) {
            repoPath = repoPath.substring(0, repoPath.length() - 4);
        }
        
        logger.info("Connecting to GitHub repository at {}: {}", githubHost, repoPath);
        
        // Configure GitHub client based on host
        if (githubHost.equals("github.com")) {
            github = new GitHubBuilder().withOAuthToken(token).build();
        } else {
            // Enterprise GitHub instance
            github = new GitHubBuilder()
                .withEndpoint("https://" + githubHost + "/api/v3")
                .withOAuthToken(token)
                .build();
        }
        
        repository = github.getRepository(repoPath);
        logger.info("Successfully connected to repository");
        
        // Initialize JGit
        git = Git.open(repoDir);
        gitRepo = git.getRepository();
    }

    public List<PullRequest> getPullRequestsBetweenTags(String fromTag, String toTag) throws IOException {
        List<PullRequest> pullRequests = new ArrayList<>();
        
        logger.info("Getting commit hashes for tags {} and {}", fromTag, toTag);
        
        try (RevWalk walk = new RevWalk(gitRepo)) {
            // Get the commit objects from the local repository
            ObjectId fromId = gitRepo.resolve(fromTag);
            ObjectId toId = gitRepo.resolve(toTag);
            
            if (fromId == null || toId == null) {
                throw new IllegalArgumentException(String.format(
                    "Could not resolve tags. From tag '%s' resolved to: %s, To tag '%s' resolved to: %s",
                    fromTag, fromId, toTag, toId));
            }
            
            // Peel tags to get their underlying commits
            RevObject fromObj = walk.parseAny(fromId);
            while (fromObj instanceof RevTag) {
                fromObj = walk.peel(fromObj);
            }
            
            RevObject toObj = walk.parseAny(toId);
            while (toObj instanceof RevTag) {
                toObj = walk.peel(toObj);
            }
            
            if (!(fromObj instanceof RevCommit) || !(toObj instanceof RevCommit)) {
                throw new IllegalArgumentException(String.format(
                    "Could not resolve tags to commits. From tag '%s' resolved to %s, To tag '%s' resolved to %s",
                    fromTag, fromObj.getClass().getSimpleName(),
                    toTag, toObj.getClass().getSimpleName()));
            }
            
            RevCommit fromCommit = (RevCommit) fromObj;
            RevCommit toCommit = (RevCommit) toObj;
            
            logger.debug("Resolved from tag '{}' to commit: {}", fromTag, fromCommit.getName());
            logger.debug("Resolved to tag '{}' to commit: {}", toTag, toCommit.getName());
            
            // Use JGit to get all non-merge commits
            Iterable<RevCommit> commits = git.log()
                .add(toCommit)
                .not(fromCommit)
                .call();
            
            // For each commit, try to find its associated PR
            for (RevCommit commit : commits) {
                if (commit.getParentCount() <= 1) { // Skip merge commits
                    String message = commit.getFullMessage();
                    logger.debug("Processing commit {} with message: {}", commit.getName(), message);
                    
                    try {
                        String prNumber = extractPRNumber(message);
                        
                        if (prNumber != null) {
                            logger.debug("Found PR number {} in commit {}", prNumber, commit.getName());
                            try {
                                GHPullRequest ghPr = getPullRequestWithRetry(Integer.parseInt(prNumber));
                                if (ghPr != null && ghPr.isMerged()) {
                                    logger.debug("Found PR #{}: {}", ghPr.getNumber(), ghPr.getTitle());
                                    PullRequest pr = createPullRequest(ghPr);
                                    pullRequests.add(pr);
                                } else {
                                    logger.debug("PR #{} not found or not merged", prNumber);
                                }
                            } catch (Exception e) {
                                logger.error("Failed to get PR details for PR#{} for commit {}", prNumber, commit.getName(), e);
                            }
                        } else {
                            logger.debug("No PR number found in commit {} with message: {}", commit.getName(), message);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to process commit {}", commit.getName(), e);
                    }
                }
            }
        } catch (GitAPIException e) {
            throw new IOException("Failed to get commits: " + e.getMessage(), e);
        }
        
        logger.info("Found {} pull requests", pullRequests.size());
        return pullRequests;
    }
    
    private String extractPRNumber(String message) {
        // Common PR reference patterns
        String[] patterns = {
            "\\(#(\\d+)\\)",                   // (#1234)
            "Merge pull request #(\\d+)"      // Merge pull request #1234
        };
        
        for (String pattern : patterns) {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(message);
            if (m.find()) {
                String prNumber = m.group(1);
                logger.debug("Found PR number {} using pattern: {}", prNumber, pattern);
                return prNumber;
            }
        }
        
        return null;
    }

    private GHPullRequest getPullRequestWithRetry(int prNumber) {
        int maxRetries = 3;
        int retryDelayMs = 1000;
        
        for (int i = 0; i < maxRetries; i++) {
            try {
                logger.trace("Attempting to get PR #{} (attempt {}/{})", prNumber, i + 1, maxRetries);
                GHPullRequest pr = repository.getPullRequest(prNumber);
                logger.trace("Successfully retrieved PR #{}", prNumber);
                return pr;
            } catch (IOException e) {
                String message = e.getMessage();
                if (message != null) {
                    if (message.contains("\"message\":\"Not Found\"")) {
                        // PR not found, no need to retry
                        logger.trace("PR #{} not found", prNumber);
                        return null;
                    } else if (message.contains("\"message\":\"API rate limit exceeded\"") || 
                             message.contains("403") || 
                             message.contains("rate limit")) {
                        // Rate limited, wait and retry
                        logger.warn("Rate limited while getting PR #{}, waiting {}ms before retry {}/{}",
                                prNumber, retryDelayMs, i + 1, maxRetries);
                        try {
                            Thread.sleep(retryDelayMs);
                            retryDelayMs *= 2; // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    } else {
                        // Other error, log and continue
                        logger.trace("Error getting PR #{}: {}", prNumber, message);
                        if (i < maxRetries - 1) {
                            try {
                                Thread.sleep(retryDelayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        } else {
                            return null;
                        }
                    }
                }
            }
        }
        
        logger.trace("Failed to get PR #{} after {} retries", prNumber, maxRetries);
        return null;
    }

    private void populateLineChanges(PullRequest pr, GHPullRequest ghPr) throws IOException {
        int additions = ghPr.getAdditions();
        int deletions = ghPr.getDeletions();
        // For modified lines, we'll use the number of changed files as a proxy since GitHub API
        // doesn't directly provide modified line count
        int modified = ghPr.getChangedFiles();
        pr.setLineChanges(additions, deletions, modified);
    }

    private PullRequest createPullRequest(GHPullRequest ghPr) throws IOException {
        PullRequest pr = new PullRequest(
            ghPr.getNumber(),
            ghPr.getUser().getLogin(),
            ghPr.getCreatedAt(),
            ghPr.getMergedAt(),
            ghPr.getBase().getRef(),
            ghPr.getMergeCommitSha(),
            ghPr.getTitle()
        );
        populateLineChanges(pr, ghPr);
        return pr;
    }
}
