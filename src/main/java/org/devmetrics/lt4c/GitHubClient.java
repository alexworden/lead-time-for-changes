package org.devmetrics.lt4c;

import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class GitHubClient {
    private static final Logger logger = LoggerFactory.getLogger(GitHubClient.class);
    private final GitHub github;
    private final GHRepository repository;

    public GitHubClient(String token, String repoUrl) throws IOException {
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
        
        logger.debug("Connecting to GitHub repository at {}: {}", githubHost, repoPath);
        
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
        logger.debug("Successfully connected to repository");
    }

    /**
     * Get pull requests between two tags
     */
    public List<PullRequest> getPullRequestsBetweenTags(String fromTag, String toTag) throws IOException {
        List<PullRequest> pullRequests = new ArrayList<>();
        Set<String> processedPRs = new HashSet<>();
        Set<String> processedCommits = new HashSet<>();
        List<String> commitsToProcess = new ArrayList<>();
        
        try {
            logger.info("Comparing tags {} to {}", fromTag, toTag);
            long startTime = System.currentTimeMillis();
            
            // Get the comparison between tags
            GHCompare compare = repository.getCompare(fromTag, toTag);
            
            // First collect all commits we need to process
            logger.info("Found {} commits between tags, collecting branch commits...", compare.getCommits().length);
            for (GHCommit commit : compare.getCommits()) {
                logger.info("Fetching commit {}", commit.getSHA1());
                if (processedCommits.contains(commit.getSHA1())) {
                    continue;
                }
                processCommitAndParents(commit, fromTag, processedCommits, commitsToProcess, 0, 3);
            }
            
            long commitCollectionTime = System.currentTimeMillis();
            logger.info("Collected {} unique commits in {}", commitsToProcess.size(), formatDuration(commitCollectionTime - startTime));
            
            // Now find PRs for all commits in one pass
            findPullRequestsForCommits(commitsToProcess, processedPRs, pullRequests);
            
            long endTime = System.currentTimeMillis();
            logger.info("Total processing time: {} (commit collection: {}, PR matching: {})", 
                       formatDuration(endTime - startTime),
                       formatDuration(commitCollectionTime - startTime),
                       formatDuration(endTime - commitCollectionTime));
            
            return pullRequests;
            
        } catch (GHFileNotFoundException e) {
            throw new IOException("Could not compare tags. Please ensure both tags exist and are accessible.", e);
        } catch (Exception e) {
            throw new IOException("Error retrieving pull requests between tags: " + e.getMessage(), e);
        }
    }
    
    /**
     * Find pull requests associated with a list of commits using the GitHub API
     */
    private void findPullRequestsForCommits(List<String> commits, Set<String> processedPRs, List<PullRequest> pullRequests) throws IOException {
        logger.info("Finding PRs for {} commits", commits.size());
        long startTime = System.currentTimeMillis();
        int prCount = 0;
        int skippedCommits = 0;
        
        for (String commitSha : commits) {
            logger.debug("Checking for PRs associated with commit {}", commitSha);
            try {
                GHCommit commit = repository.getCommit(commitSha);
                List<GHPullRequest> prs = commit.listPullRequests().toList();
                if (prs == null || prs.isEmpty()) {
                    logger.debug("No PRs found for commit {}", commitSha);
                    continue;
                }
                for (GHPullRequest pr : prs) {
                    if (!pr.isMerged() || processedPRs.contains(String.valueOf(pr.getNumber()))) {
                        logger.debug("PR #{} is not merged or already processed, skipping", pr.getNumber());
                        continue;
                    }
                    
                    processedPRs.add(String.valueOf(pr.getNumber()));
                    pullRequests.add(createPullRequest(pr));
                    prCount++;
                    logger.debug("Found PR #{} associated with commit {} ({})", 
                        pr.getNumber(), commitSha.substring(0, 8), pr.getTitle());
                }
            } catch (GHFileNotFoundException e) {
                // Commit might not exist or be accessible
                logger.warn("Could not find commit {} - commit may have been deleted: {}", commitSha, e.getMessage());
                skippedCommits++;
            } catch (IOException e) {
                // Other API errors
                logger.warn("Error processing commit {} - skipping: {}", commitSha, e.getMessage());
                skippedCommits++;
            }
        }
        
        long endTime = System.currentTimeMillis();
        logger.info("Found {} PRs in {} ({} commits skipped)", 
            prCount, formatDuration(endTime - startTime), skippedCommits);
    }
    
    /**
     * Recursively collect commits from a source branch, with a limit on recursion depth
     * @param commit The commit to start processing from
     * @param stopAtTag The tag to stop processing at
     * @param processedCommits Set of already processed commit SHAs
     * @param commitsToProcess List to add new commit SHAs to
     * @param currentDepth Current recursion depth
     * @param maxDepth Maximum recursion depth to prevent infinite loops
     */
    private void processCommitAndParents(GHCommit commit, String stopAtTag, 
                                    Set<String> processedCommits, List<String> commitsToProcess,
                                    int currentDepth, int maxDepth) throws IOException {
        try {
            // Stop if we've hit the recursion limit
            if (currentDepth >= maxDepth) {
                logger.debug("{} Reached maximum recursion depth ({}) at commit {}", 
                    getDepthPrefix(currentDepth), maxDepth, commit.getSHA1());
                return;
            }

            // Stop if we've already processed this commit
            if (processedCommits.contains(commit.getSHA1())) {
                return;
            }

            processedCommits.add(commit.getSHA1());
            commitsToProcess.add(commit.getSHA1());
            
            // Get parents
            List<GHCommit> parents;
            try {
                parents = commit.getParents();
            } catch (GHFileNotFoundException e) {
                logger.warn("{} Could not fetch parents for commit {} - commit may have been deleted: {}", 
                    getDepthPrefix(currentDepth), commit.getSHA1(), e.getMessage());
                return;
            }
            
            if (parents.isEmpty()) {
                return;
            }

            // For merge commits, recursively process the source branch (second parent)
            if (parents.size() > 1) {
                GHCommit sourceBranchCommit;
                try {
                    sourceBranchCommit = parents.get(1);
                    
                    if (logger.isDebugEnabled()) {
                        // Try to get branch name from associated PR
                        List<GHPullRequest> prs = sourceBranchCommit.listPullRequests().toList();
                        String branchInfo = prs.isEmpty() ? "unknown branch" : 
                            String.format("branch '%s' (PR #%d)", prs.get(0).getHead().getRef(), prs.get(0).getNumber());
                        logger.debug("{} Processing commit {} from {} (depth: {})", 
                            getDepthPrefix(currentDepth), sourceBranchCommit.getSHA1().substring(0, 8), 
                            branchInfo, currentDepth);
                    }
                    
                    if (!processedCommits.contains(sourceBranchCommit.getSHA1())) {
                        processCommitAndParents(sourceBranchCommit, stopAtTag, 
                            processedCommits, commitsToProcess, currentDepth + 1, maxDepth);
                    }
                } catch (GHFileNotFoundException e) {
                    logger.warn("{} Could not fetch source branch commit {} - commit may have been deleted: {}", 
                        getDepthPrefix(currentDepth), parents.get(1).getSHA1(), e.getMessage());
                }
            }
            
            // Continue with the first parent (main branch line)
            try {
                GHCommit parentCommit = parents.get(0);
                if (!processedCommits.contains(parentCommit.getSHA1())) {
                    processCommitAndParents(parentCommit, stopAtTag, 
                        processedCommits, commitsToProcess, currentDepth + 1, maxDepth);
                }
            } catch (GHFileNotFoundException e) {
                logger.warn("{} Could not fetch parent commit {} - commit may have been deleted: {}", 
                    getDepthPrefix(currentDepth), parents.get(0).getSHA1(), e.getMessage());
            }
        } catch (GHFileNotFoundException e) {
            // Stop processing if we can't find the commit (likely hit the repository boundary)
            logger.warn("{} Could not fetch commit {} - commit may have been deleted: {}", 
                getDepthPrefix(currentDepth), commit.getSHA1(), e.getMessage());
        }
    }

    /**
     * Get a prefix string of '>' characters based on the current depth
     * @param depth Current recursion depth
     * @return String of '>' characters, two per depth level
     */
    private String getDepthPrefix(int depth) {
        if (depth <= 0) return "";
        return ">".repeat(depth * 2);
    }

    /**
     * Create a PullRequest object from a GitHub pull request
     */
    private PullRequest createPullRequest(GHPullRequest ghPr) throws IOException {
        return new PullRequest(
            ghPr.getNumber(),
            ghPr.getTitle(),
            ghPr.getUser().getLogin(),
            ghPr.getBase().getRef(),
            ghPr.getMergeCommitSha(),
            ghPr.getCreatedAt(),
            ghPr.getMergedAt(),
            ghPr.getAdditions(),
            ghPr.getDeletions(),
            ghPr.getBody()
        );
    }

    /**
     * Find the previous release tag for a given tag
     */
    public String findPreviousReleaseTag(String releaseTag) throws IOException {
        ReleaseLocator locator = new ReleaseLocator(repository);
        return locator.findPreviousReleaseTag(releaseTag);
    }

    /**
     * Get the GitHub repository instance
     */
    public GHRepository getRepository() {
        return repository;
    }

    /**
     * Format a duration in milliseconds to a human-readable string
     * @param durationMs Duration in milliseconds
     * @return Formatted string like "2m 30s" or "500ms"
     */
    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        
        StringBuilder result = new StringBuilder();
        long minutes = durationMs / (60 * 1000);
        long seconds = (durationMs % (60 * 1000)) / 1000;
        long remainingMs = durationMs % 1000;
        
        if (minutes > 0) {
            result.append(minutes).append("m ");
        }
        if (seconds > 0 || minutes > 0) {
            result.append(seconds).append("s");
            if (remainingMs > 0) {
                result.append(" ").append(remainingMs).append("ms");
            }
        }
        
        return result.toString();
    }
}
