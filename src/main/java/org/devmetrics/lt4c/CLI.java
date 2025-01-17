package org.devmetrics.lt4c;

import org.apache.commons.cli.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

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

            File repoDir;
            if (githubUrl != null) {
                repoDir = getOrCreateGitRepo(githubUrl, token);
            } else {
                repoDir = new File(directory);
                try {
                    // Try to open the Git repository to validate it
                    Git.open(repoDir);
                } catch (Exception e) {
                    throw new ParseException("Invalid Git repository directory: " + directory + " - " + e.getMessage());
                }
            }

            LeadTimeAnalyzer analyzer = new LeadTimeAnalyzer(repoDir);
            
            // If we have a GitHub URL and token, set up the GitHub client
            if (githubUrl != null && token != null) {
                try {
                    logger.info("Initializing GitHub client with URL: {} and token: {}", githubUrl, token != null ? "present" : "missing");
                    GitHubClient githubClient = new GitHubClient(token, githubUrl, repoDir);
                    analyzer.setGitHubClient(githubClient);
                    logger.info("GitHub client initialized successfully");
                } catch (IOException e) {
                    logger.error("Failed to initialize GitHub client", e);
                    System.err.println("Warning: Failed to initialize GitHub client. Falling back to git log analysis: " + e.getMessage());
                }
            } else {
                logger.info("Skipping GitHub client initialization. URL: {}, Token: {}", 
                    githubUrl != null ? githubUrl : "missing",
                    token != null ? "present" : "missing");
            }

            analyzer.analyzeRelease(cmd.getOptionValue("end-tag"), cmd.getOptionValue("start-tag"));
        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            formatter.printHelp("lead-time-analyzer", options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static File getOrCreateGitRepo(String repoUrl, String token) throws Exception {
        try {
            // Create base directory for cached repositories
            File baseDir = new File(DEFAULT_CACHE_DIR);
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }

            // Convert URL to directory name
            String repoName = repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
            File repoDir = new File(baseDir, repoName);

            if (!repoDir.exists()) {
                // Clone the repository if it doesn't exist
                logger.info("Cloning repository from {}...", repoUrl);
                Git.cloneRepository()
                    .setURI(repoUrl)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                    .setDirectory(repoDir)
                    .call();
                logger.info("Repository cloned successfully");
            } else {
                // Update existing repository
                logger.info("Updating existing repository clone...");
                Git git = Git.open(repoDir);
                git.fetch().setRemote("origin").setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, "")).call();
                logger.info("Repository updated successfully");
            }

            return repoDir;
        } catch (Exception e) {
            throw new Exception("Failed to prepare git repository: " + e.getMessage(), e);
        }
    }
}
