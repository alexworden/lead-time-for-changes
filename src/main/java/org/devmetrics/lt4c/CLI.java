package org.devmetrics.lt4c;

import org.apache.commons.cli.*;
import org.eclipse.jgit.api.Git;
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
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

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

        options.addOption(Option.builder("s")
                .longOpt("start-tag")
                .desc("Start tag")
                .hasArg()
                .required()
                .build());

        options.addOption(Option.builder("e")
                .longOpt("end-tag")
                .desc("End tag")
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
            String startTag = cmd.getOptionValue("start-tag");
            String endTag = cmd.getOptionValue("end-tag");

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

            // Analyze the release
            ReleaseAnalysis analysis = analyzer.analyzeRelease(endTag, startTag);
            printAnalysisResults(analysis);

        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            formatter.printHelp("leadtime", options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void setupGithubClient(String githubUrl, String token, File repoDir, LeadTimeAnalyzer analyzer) {
        // Set up GitHub client if we have a URL and token
        if (token != null && (githubUrl != null || isGitHubRepo(repoDir))) {
            String url = githubUrl;
            if (url == null) {
                // Try to get GitHub URL from git config
                try {
                    Git git = Git.open(repoDir);
                    url = git.getRepository().getConfig().getString("remote", "origin", "url");
                    git.close();
                } catch (Exception e) {
                    logger.warn("Failed to get GitHub URL from git config: {}", e.getMessage());
                }
            }
            
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

    private static boolean isGitHubRepo(File repoDir) {
        try {
            Git git = Git.open(repoDir);
            String url = git.getRepository().getConfig().getString("remote", "origin", "url");
            git.close();
            return url != null && url.contains("github.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static File getOrCreateGitRepo(String githubUrl, String token) throws IOException, GitAPIException {
        File cacheDir = new File(DEFAULT_CACHE_DIR);
        String repoName = githubUrl.replaceAll(".*github\\.com[:/]", "").replaceAll("\\.git$", "").replace('/', '-');
        File repoDir = new File(cacheDir, repoName);

        if (!repoDir.exists()) {
            logger.info("Cloning repository {} to {}", githubUrl, repoDir);
            cacheDir.mkdirs();
            Git.cloneRepository()
                    .setURI(githubUrl)
                    .setDirectory(repoDir)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                    .call()
                    .close();
        } else {
            logger.info("Using existing repository at {}", repoDir);
            Git git = Git.open(repoDir);
            try {
                logger.info("Fetching latest changes...");
                git.fetch()
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
        System.out.printf("Release Analysis for %s (commit: %s)%n", 
            analysis.getReleaseTag(), 
            analysis.getReleaseCommit());
        System.out.printf("Release Date: %s%n", DATE_FORMAT.format(analysis.getReleaseDate()));
        System.out.printf("Number of Pull Requests: %d%n", analysis.getPullRequests().size());
        System.out.printf("Average Lead Time: %.2f hours%n", analysis.getAverageLeadTimeHours());
        System.out.printf("Median Lead Time: %.2f hours%n", analysis.getMedianLeadTimeHours());
        System.out.printf("90th Percentile Lead Time: %.2f hours%n", analysis.getP90LeadTimeHours());
        System.out.println("\nPull Requests:");
        
        if (!analysis.getPullRequests().isEmpty()) {
            for (PullRequest pr : analysis.getPullRequests()) {
                System.out.printf("PR #%d by %s%n", pr.getNumber(), pr.getAuthor());
                System.out.printf("  Merged at: %s%n", DATE_FORMAT.format(pr.getMergedAt()));
                System.out.printf("  Lead Time: %.2f hours%n", pr.getLeadTimeHours());
                System.out.printf("  Target Branch: %s%n", pr.getTargetBranch());
                System.out.printf("  Commit: %s%n", pr.getMergeSha());
                System.out.printf("  Comment: %s%n", pr.getComment().split("\n")[0]);
                System.out.println();
            }
        }
    }
}
