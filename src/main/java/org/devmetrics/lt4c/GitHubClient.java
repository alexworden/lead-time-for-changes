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
                processedCommits.add(commit.getSHA1());
                
                if (commit.getParents().size() > 1) {  // This is a merge commit
                    // Get the source branch commit (second parent in a merge)
                    String sourceBranchCommit = commit.getParents().get(1).getSHA1();
                    logger.info("  This is a merge commit, fetching source branch commit...");
                    if (!processedCommits.contains(sourceBranchCommit)) {
                        commitsToProcess.add(sourceBranchCommit);
                        collectBranchCommits(sourceBranchCommit, fromTag, processedCommits, commitsToProcess);
                    }
                } else {
                    commitsToProcess.add(commit.getSHA1());
                }
            }
            
            long commitCollectionTime = System.currentTimeMillis();
            logger.info("Collected {} unique commits in {}ms", commitsToProcess.size(), 
                       commitCollectionTime - startTime);
            
            // Now find PRs for all commits in one pass
            findPullRequestsForCommits(commitsToProcess, processedPRs, pullRequests);
            
            long endTime = System.currentTimeMillis();
            logger.info("Total processing time: {}ms (commit collection: {}ms, PR matching: {}ms)", 
                       endTime - startTime,
                       commitCollectionTime - startTime,
                       endTime - commitCollectionTime);
            
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
        
        for (String commitSha : commits) {
            logger.debug("Checking for PRs associated with commit {}", commitSha);
            try {
                List<GHPullRequest> prs = repository.getCommit(commitSha).listPullRequests().toList();
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
                    logger.debug("Found PR #{} associated with commit {}", pr.getNumber(), commitSha);
                }
            } catch (GHFileNotFoundException e) {
                // Commit might not exist or be accessible
                logger.warn("Could not find commit {} or its associated PRs: {}", commitSha, e.getMessage());
            }
        }
        
        long endTime = System.currentTimeMillis();
        logger.info("Found {} PRs in {}ms", prCount, endTime - startTime);
    }
    
    /**
     * Recursively collect commits from a source branch
     */
    private void collectBranchCommits(String startCommit, String stopAtTag, 
                                    Set<String> processedCommits, List<String> commitsToProcess) throws IOException {
        try {
            GHCommit commit = repository.getCommit(startCommit);
            
            // Stop if we've hit the from tag or already processed this commit
            if (processedCommits.contains(commit.getSHA1())) {
                return;
            }
            processedCommits.add(commit.getSHA1());
            commitsToProcess.add(commit.getSHA1());
            
            // Try to get branch name from associated PR
            List<GHPullRequest> prs = commit.listPullRequests().toList();
            String branchInfo = prs.isEmpty() ? "unknown branch" : 
                String.format("branch '%s' (PR #%d)", prs.get(0).getHead().getRef(), prs.get(0).getNumber());
            logger.info("    Processing commit {} from {}", commit.getSHA1().substring(0, 8), branchInfo);
            
            // For merge commits, recursively process the source branch
            if (commit.getParents().size() > 1) {
                String sourceBranchCommit = commit.getParents().get(1).getSHA1();
                if (!processedCommits.contains(sourceBranchCommit)) {
                    commitsToProcess.add(sourceBranchCommit);
                    collectBranchCommits(sourceBranchCommit, stopAtTag, processedCommits, commitsToProcess);
                }
            }
            
            // Continue with the first parent (main branch line)
            if (!commit.getParents().isEmpty()) {
                String parentCommit = commit.getParents().get(0).getSHA1();
                if (!processedCommits.contains(parentCommit)) {
                    collectBranchCommits(parentCommit, stopAtTag, processedCommits, commitsToProcess);
                }
            }
        } catch (GHFileNotFoundException e) {
            // Stop processing if we can't find the commit (likely hit the repository boundary)
            logger.debug("Reached repository boundary at commit {}", startCommit);
        }
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
            ghPr.getDeletions()
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
}
