package org.devmetrics.lt4c;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import java.io.IOException;

public class CLI {
    private static final Logger logger = LoggerFactory.getLogger(CLI.class);

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder("u")
                .longOpt("github-url")
                .desc("GitHub repository URL")
                .hasArg()
                .required()
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

        options.addOption(Option.builder("g")
                .longOpt("debug")
                .desc("Enable debug logging")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);
            String githubUrl = cmd.getOptionValue("github-url");
            String token = cmd.getOptionValue("token", System.getenv("LT4C_GIT_TOKEN"));
            String fromRelease = cmd.getOptionValue("from-release");
            String targetRelease = cmd.getOptionValue("target-release");
            
            if (token == null) {
                throw new ParseException("GitHub token must be provided via --token or LT4C_GIT_TOKEN environment variable");
            }
            
            // Set logging level based on debug flag
            if (cmd.hasOption("debug")) {
                Logger logger = LoggerFactory.getLogger("org.devmetrics");
                if (logger instanceof ch.qos.logback.classic.Logger) {
                    ((ch.qos.logback.classic.Logger) logger).setLevel(Level.TRACE);
                }
            }

            // Initialize GitHub client
            GitHubClient githubClient = createGitHubClient(token, githubUrl);
            
            // Initialize the analyzer with GitHub client
            LeadTimeAnalyzer analyzer = new LeadTimeAnalyzer(githubClient);

            // If no from-release specified, find the previous release
            if (fromRelease == null) {
                logger.info("No --from-release specified, finding previous tag before target release: {}", targetRelease);
                fromRelease = githubClient.findPreviousReleaseTag(targetRelease);
                if (fromRelease == null) {
                    throw new Exception("Could not find previous release tag before target release: " + targetRelease);
                }
                logger.info("Found previous tag: {}", fromRelease);
            }

            // Analyze the release
            ReleaseAnalysis analysis = analyzer.analyzeRelease(targetRelease, fromRelease);
            printAnalysisResults(analysis);

        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            formatter.printHelp("lt4c", 
                "\nAnalyze lead time for changes between releases in a GitHub repository.\n\n" +
                "Example:\n" +
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

    private static GitHubClient createGitHubClient(String token, String repoUrl) throws IOException {
        try {
            GitHubClient githubClient = new GitHubClient(token, repoUrl);
            logger.info("Successfully connected to GitHub");
            return githubClient;
        } catch (IOException e) {
            logger.error("Failed to connect to GitHub: {}", e.getMessage());
            throw e;
        }
    }

    private static void printAnalysisResults(ReleaseAnalysis analysis) {
        // Print individual PR details
        for (PullRequest pr : analysis.getPullRequests()) {
            outputPullRequestDetails(pr);
        }

        System.out.println("\nSummary:");
        System.out.println("==========");
        System.out.printf("Release %s to %s%n", analysis.getFromReleaseTag(), analysis.getReleaseTag());
        System.out.printf("Time Period: %s to %s%n", 
            analysis.getFromReleaseDate(), analysis.getReleaseDate());
        System.out.printf("Total Pull Requests: %d%n", analysis.getTotalPullRequests());

        // Lead Time Metrics
        System.out.println("\nLead Time Metrics:");
        System.out.printf("  * Average: %.1f hours (%.1f days)%n", 
            analysis.getAverageLeadTimeHours(), 
            analysis.getAverageLeadTimeHours() / 24.0);
        System.out.printf("  * Median: %.1f hours (%.1f days)%n", 
            analysis.getMedianLeadTimeHours(), 
            analysis.getMedianLeadTimeHours() / 24.0);
        System.out.printf("  * 90th percentile: %.1f hours (%.1f days)%n", 
            analysis.getP90LeadTimeHours(), 
            analysis.getP90LeadTimeHours() / 24.0);

        // Line Changes
        System.out.println("\nLine Changes:");
        System.out.printf("  * Added: %,d lines%n", analysis.getTotalLinesAdded());
        System.out.printf("  * Deleted: %,d lines%n", analysis.getTotalLinesDeleted());
        System.out.printf("  * Total Changes: %,d lines%n", analysis.getTotalLinesChanged());
        System.out.printf("  * Average Changes per PR: %.2f lines%n", analysis.getAverageLinesChanged());

        // Lead Time Distribution
        int fastCount = 0, mediumCount = 0, slowCount = 0;
        for (PullRequest pr : analysis.getPullRequests()) {
            double leadTime = pr.getLeadTimeHours();
            if (leadTime < 24) fastCount++;
            else if (leadTime < 72) mediumCount++;
            else slowCount++;
        }
        int total = analysis.getTotalPullRequests();
        
        System.out.println("\nLead Time Distribution:");
        System.out.printf("  * Fast (< 24 hours): %d PRs (%.1f%%)%n", 
            fastCount, (fastCount * 100.0) / total);
        System.out.printf("  * Medium (24-72 hours): %d PRs (%.1f%%)%n", 
            mediumCount, (mediumCount * 100.0) / total);
        System.out.printf("  * Slow (> 72 hours): %d PRs (%.1f%%)%n", 
            slowCount, (slowCount * 100.0) / total);
    }

    private static void outputPullRequestDetails(PullRequest pr) {
        System.out.print(pr.toString());
    }
}
