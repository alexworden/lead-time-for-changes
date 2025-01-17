package org.devmetrics.lt4c;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
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
        
        try {
            // Get the commit objects
            String fromCommit = repository.getRef("tags/" + fromTag).getObject().getSha();
            String toCommit = repository.getRef("tags/" + toTag).getObject().getSha();
            
            logger.info("Getting commits between {} and {}", fromCommit, toCommit);
            
            // Use JGit to get all non-merge commits
            Iterable<RevCommit> commits = git.log()
                .add(gitRepo.resolve(toCommit))
                .not(gitRepo.resolve(fromCommit))
                .call();
            
            // For each commit, try to find its associated PR
            for (RevCommit commit : commits) {
                if (commit.getParentCount() <= 1) { // Skip merge commits
                    String message = commit.getFullMessage();
                    logger.trace("Processing commit {} with message: {}", commit.getName(), message);
                    
                    try {
                        String prNumber = extractPRNumber(message);
                        
                        if (prNumber != null) {
                            logger.trace("Found PR number {} in commit {}", prNumber, commit.getName());
                            try {
                                GHPullRequest ghPr = getPullRequestWithRetry(Integer.parseInt(prNumber));
                                if (ghPr != null && ghPr.isMerged()) {
                                    logger.trace("Found PR #{}: {}", ghPr.getNumber(), ghPr.getTitle());
                                    PullRequest pr = new PullRequest(
                                            ghPr.getNumber(),
                                            ghPr.getUser().getLogin(),
                                            ghPr.getMergedAt(),
                                            ghPr.getBase().getRef(),
                                            ghPr.getMergeCommitSha(),
                                            ghPr.getTitle() + "\n" + (ghPr.getBody() != null ? ghPr.getBody() : "")
                                    );
                                    pullRequests.add(pr);
                                } else {
                                    // PR not found or not accessible, create one from commit info
                                    logger.trace("Creating PR from commit info for #{}", prNumber);
                                    PullRequest pr = new PullRequest(
                                            Integer.parseInt(prNumber),
                                            commit.getAuthorIdent().getName(),
                                            new Date(commit.getCommitTime() * 1000L),
                                            "main", // Assuming main branch
                                            commit.getName(),
                                            message
                                    );
                                    pullRequests.add(pr);
                                }
                            } catch (Exception e) {
                                logger.trace("Failed to get PR details for #{}: {}", prNumber, e.getMessage());
                                // Create PR from commit info as fallback
                                logger.trace("Creating PR from commit info for #{} after error", prNumber);
                                PullRequest pr = new PullRequest(
                                        Integer.parseInt(prNumber),
                                        commit.getAuthorIdent().getName(),
                                        new Date(commit.getCommitTime() * 1000L),
                                        "main", // Assuming main branch
                                        commit.getName(),
                                        message
                                );
                                pullRequests.add(pr);
                            }
                        }
                    } catch (Exception e) {
                        logger.trace("Failed to process commit {}: {}", commit.getName(), e.getMessage());
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
            "Merge pull request #(\\d+)",      // Merge pull request #1234
            "(?i)Closes gh-(\\d+)",            // Keep existing patterns as fallback
            "(?i)Fixes gh-(\\d+)",
            "(?i)Resolves gh-(\\d+)",
            "(?i)Close gh-(\\d+)",
            "(?i)Fix gh-(\\d+)",
            "(?i)Resolve gh-(\\d+)",
            "(?i)See gh-(\\d+)",
            "(?i)gh-(\\d+)"
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
}
