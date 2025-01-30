package org.devmetrics.lt4c;

import org.apache.commons.cli.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;

public class CLI {
    private static final Logger logger = LoggerFactory.getLogger(CLI.class);
    private static final String DEFAULT_CACHE_DIR = System.getProperty("user.home") + "/.leadtime/repos";

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder("d")
                .longOpt("directory")
                .desc("Git repository directory")
                .hasArg()
                .build());

        options.addOption(Option.builder("u")
                .longOpt("github-url")
                .desc("GitHub repository URL")
                .hasArg()
                .build());

        options.addOption(Option.builder("t")
                .longOpt("token")
                .desc("GitHub token (or set LT4C_GIT_TOKEN env var)")
                .hasArg()
                .build());

        options.addOption(Option.builder("fr")
                .longOpt("from-release")
                .desc("From release (optional starting point)")
                .hasArg()
                .build());

        options.addOption(Option.builder("tr")
                .longOpt("target-release")
                .desc("Target release")
                .hasArg()
                .required()
                .build());

        options.addOption(Option.builder("l")
                .longOpt("limit")
                .desc("Limit number of releases to analyze")
                .hasArg()
                .build());

        options.addOption(Option.builder("g")
                .longOpt("debug")
                .desc("Enable debug logging")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);
            String directory = cmd.getOptionValue("directory");
            String githubUrl = cmd.getOptionValue("github-url");
            String token = cmd.getOptionValue("token", System.getenv("LT4C_GIT_TOKEN"));
            String fromRelease = cmd.getOptionValue("from-release");
            String targetRelease = cmd.getOptionValue("target-release");

            File repoDir;
            if (githubUrl != null) {
                repoDir = getOrCreateGitRepo(githubUrl, token);
            } else if (directory != null) {
                repoDir = new File(directory);
                try {
                    // Try to open the Git repository to validate it
                    Git git = Git.open(repoDir);
                    
                    // Try to fetch tags if we have a token
                    if (token != null) {
                        logger.info("Attempting to fetch tags for local repository: {}", repoDir);
                        try {
                            git.fetch()
                                .setRefSpecs("+refs/tags/*:refs/tags/*")
                                .setTagOpt(TagOpt.FETCH_TAGS)
                                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                                .call();
                            logger.info("Tags fetched successfully");
                        } catch (GitAPIException e) {
                            logger.warn("Failed to fetch tags: {}. Will use local tags only.", e.getMessage());
                        }
                    } else {
                        logger.info("No GitHub token provided, will use local tags only");
                    }
                    git.close();
                } catch (Exception e) {
                    throw new ParseException("Invalid Git repository directory: " + directory + " - " + e.getMessage());
                }
            } else {
                throw new ParseException("Either --directory or --github-url must be specified");
            }

            // Initialize the analyzer
            LeadTimeAnalyzer analyzer = new LeadTimeAnalyzer(repoDir);
            
            setupGithubClient(githubUrl, token, repoDir, analyzer);

            if (fromRelease == null) {
                logger.info("No --from-release specified, finding previous tag before target release: {}", targetRelease);
                // Open git repository to find previous tag
                Git git = Git.open(repoDir);
                try {
                    // Find the previous release tag
                    ReleaseLocator locator = new ReleaseLocator(git);
                    String previousTag = locator.findPreviousReleaseTag(targetRelease);
                    logger.info("Found previous tag for {} is tag: {}", targetRelease, previousTag);
                    fromRelease = previousTag;
                } finally {
                    git.close();
                }
            }

            // Analyze the release
            ReleaseAnalysis analysis = analyzer.analyzeRelease(targetRelease, fromRelease);
            printAnalysisResults(analysis);

        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            formatter.printHelp("lt4c", 
                "\nAnalyze lead time for changes between releases in a Git repository.\n\n" +
                "Examples:\n" +
                "  lt4c --directory /path/to/repo --target-release v1.0.0\n" +
                "  lt4c --github-url https://github.com/org/repo --target-release v1.0.0 --from-release v0.9.0\n\n",
                options,
                "\nNote: If --from-release is not specified, the previous release tag will be automatically detected.",
                true);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void setupGithubClient(String githubUrl, String token, File repoDir, LeadTimeAnalyzer analyzer) {
        // Set up GitHub client if we have a token
        if (token != null) {
            String url = githubUrl != null ? githubUrl : getGitHubUrl(repoDir);
            
            if (url != null) {
                try {
                    logger.info("Initializing GitHub client with URL: {} and token: {}", url, token != null ? "present" : "missing");
                    GitHubClient githubClient = new GitHubClient(token, url, repoDir);
                    analyzer.setGitHubClient(githubClient);
                    logger.info("GitHub client initialized successfully");
                } catch (IOException e) {
                    logger.error("Failed to initialize GitHub client", e);
                    System.err.println("Warning: Failed to initialize GitHub client. Falling back to git log analysis: " + e.getMessage());
                }
            }
        }
    }

    private static String getGitHubUrl(File repoDir) {
        try {
            Git git = Git.open(repoDir);
            String url = git.getRepository().getConfig().getString("remote", "origin", "url");
            git.close();
            return url;
        } catch (Exception e) {
            logger.warn("Failed to get GitHub URL from git config: {}", e.getMessage());
            return null;
        }
    }

    private static File getOrCreateGitRepo(String githubUrl, String token) throws IOException, GitAPIException {
        File cacheDir = new File(DEFAULT_CACHE_DIR);
        // Remove protocol and domain, keeping the org/repo path
        String repoPath = githubUrl.replaceAll("^https?://[^/]+/|git@[^:]+:", "").replaceAll("\\.git$", "");
        // Split into org and repo name
        String[] parts = repoPath.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid GitHub URL format. Expected format: domain/org/repo");
        }
        String orgName = parts[0];
        String repoName = parts[1];
        
        // Create org directory under cache dir
        File orgDir = new File(cacheDir, orgName);
        File repoDir = new File(orgDir, repoName);

        if (!repoDir.exists()) {
            logger.info("Cloning repository {} to {}", githubUrl, repoDir);
            orgDir.mkdirs();
            Git.cloneRepository()
                    .setURI(githubUrl)
                    .setDirectory(repoDir)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                    .setTagOption(TagOpt.FETCH_TAGS)
                    .call()
                    .close();
        } else {
            logger.info("Using existing repository at {}", repoDir);
            Git git = Git.open(repoDir);
            try {
                logger.info("Fetching latest changes...");
                git.fetch()
                        .setRefSpecs("+refs/tags/*:refs/tags/*")
                        .setTagOpt(TagOpt.FETCH_TAGS)
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                        .call();
            } catch (GitAPIException e) {
                logger.warn("Failed to fetch latest changes: {}", e.getMessage());
            }
            git.close();
        }

        return repoDir;
    }

    private static void printAnalysisResults(ReleaseAnalysis analysis) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

        // Print individual PR details
        for (PullRequest pr : analysis.getPullRequests()) {
            System.out.println(pr.toString());
        }

        System.out.println("\nSummary:");
        System.out.println("==========");
        System.out.println("- Release " + analysis.getReleaseTag() + " was made on " + dateFormat.format(analysis.getReleaseDate()));
        System.out.println("- From Release " + analysis.getFromReleaseTag() + " was made on " + dateFormat.format(analysis.getFromReleaseDate()));
        System.out.println("- Included " + analysis.getPullRequests().size() + " pull requests");

        // Lead Time Metrics
        System.out.println("- Lead Time Metrics:");
        System.out.printf("  * Average: %.1f hours (%.1f days)%n", 
            analysis.getAverageLeadTimeHours(), 
            analysis.getAverageLeadTimeHours() / 24.0);
        System.out.printf("  * Median: %.1f hours (%.1f days)%n", 
            analysis.getMedianLeadTimeHours(), 
            analysis.getMedianLeadTimeHours() / 24.0);
        System.out.printf("  * 90th percentile: %.1f hours (%.1f days)%n", 
            analysis.getP90LeadTimeHours(), 
            analysis.getP90LeadTimeHours() / 24.0);

        // Lead Time Distribution
        int fastCount = 0, mediumCount = 0, slowCount = 0;
        for (PullRequest pr : analysis.getPullRequests()) {
            double leadTime = pr.getLeadTimeHours();
            if (leadTime < 24) fastCount++;
            else if (leadTime < 72) mediumCount++;
            else slowCount++;
        }
        int total = analysis.getPullRequests().size();
        
        System.out.println("\n- Lead Time Distribution:");
        System.out.printf("  * Fast (< 24 hours): %d PRs (%.1f%%)%n", 
            fastCount, (fastCount * 100.0) / total);
        System.out.printf("  * Medium (24-72 hours): %d PRs (%.1f%%)%n", 
            mediumCount, (mediumCount * 100.0) / total);
        System.out.printf("  * Slow (> 72 hours): %d PRs (%.1f%%)%n", 
            slowCount, (slowCount * 100.0) / total);

        System.out.println("\n- Line Change Statistics:");
        System.out.printf("  * Added: %,d lines%n", analysis.getTotalLinesAdded());
        System.out.printf("  * Deleted: %,d lines%n", analysis.getTotalLinesDeleted());
        System.out.printf("  * Modified: %,d lines%n", analysis.getTotalLinesModified());
        System.out.printf("  * Total Changes: %,d lines%n", analysis.getTotalLinesChanged());
    }
}
