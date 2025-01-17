package org.devmetrics.lt4c;

import org.apache.commons.cli.*;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class CLI {
    private static final Logger logger = LoggerFactory.getLogger(CLI.class);
    private static final String DEFAULT_CACHE_DIR = System.getProperty("user.home") + "/.leadtime/repos";

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("D")
                .longOpt("directory")
                .hasArg()
                .desc("Local Git repository directory")
                .build());
        options.addOption(Option.builder("g")
                .longOpt("github-url")
                .hasArg()
                .desc("GitHub repository URL")
                .build());
        options.addOption(Option.builder("s")
                .longOpt("start-release")
                .hasArg()
                .desc("Start release tag")
                .build());
        options.addOption(Option.builder("e")
                .longOpt("end-release")
                .hasArg()
                .desc("End release tag")
                .build());
        options.addOption(Option.builder("d")
                .longOpt("debug")
                .desc("Enable debug logging")
                .build());
        options.addOption(Option.builder("l")
                .longOpt("limit")
                .hasArg()
                .desc("Limit number of releases to analyze")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);
            String directory = cmd.getOptionValue("directory");
            String githubUrl = cmd.getOptionValue("github-url");
            String startRelease = cmd.getOptionValue("start-release");
            String endRelease = cmd.getOptionValue("end-release");

            if (directory == null && githubUrl == null) {
                throw new ParseException("Either --directory or --github-url must be specified");
            }

            if (directory != null && githubUrl != null) {
                throw new ParseException("Cannot specify both --directory and --github-url");
            }

            File repoDir;
            if (githubUrl != null) {
                repoDir = getOrCreateGitRepo(githubUrl);
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
            analyzer.analyzeRelease(endRelease, startRelease);

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

    private static File getOrCreateGitRepo(String repoUrl) throws Exception {
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
                    .setDirectory(repoDir)
                    .call();
                logger.info("Repository cloned successfully");
            } else {
                // Update existing repository
                logger.info("Updating existing repository clone...");
                Git git = Git.open(repoDir);
                git.fetch().setRemote("origin").call();
                logger.info("Repository updated successfully");
            }

            return repoDir;
        } catch (Exception e) {
            throw new Exception("Failed to prepare git repository: " + e.getMessage(), e);
        }
    }
}
