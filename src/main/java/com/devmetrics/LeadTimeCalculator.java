package com.devmetrics;

import org.apache.commons.cli.*;
import org.kohsuke.github.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

public class LeadTimeCalculator {
    private final GitHub github;
    private final String repository;
    private final String startRelease;
    private final String endRelease;

    public LeadTimeCalculator(String token, String repository, String startRelease, String endRelease) throws IOException {
        this.github = new GitHubBuilder().withOAuthToken(token).build();
        this.repository = repository;
        this.startRelease = startRelease;
        this.endRelease = endRelease;
    }

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("t")
                .longOpt("token")
                .hasArg()
                .desc("GitHub personal access token")
                .required()
                .build());
        options.addOption(Option.builder("r")
                .longOpt("repository")
                .hasArg()
                .desc("Repository in format 'owner/repo'")
                .required()
                .build());
        options.addOption(Option.builder("s")
                .longOpt("start-release")
                .hasArg()
                .desc("Start release tag (optional)")
                .build());
        options.addOption(Option.builder("e")
                .longOpt("end-release")
                .hasArg()
                .desc("End release tag (optional)")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);
            LeadTimeCalculator calculator = new LeadTimeCalculator(
                    cmd.getOptionValue("token"),
                    cmd.getOptionValue("repository"),
                    cmd.getOptionValue("start-release"),
                    cmd.getOptionValue("end-release")
            );
            calculator.calculateLeadTimes();
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("lead-time-calculator", options);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error connecting to GitHub: " + e.getMessage());
            System.exit(1);
        }
    }

    public void calculateLeadTimes() throws IOException {
        GHRepository repo = github.getRepository(repository);
        List<GHRelease> releases;
        try {
            releases = repo.listReleases().toList();
        } catch (IOException e) {
            System.err.println("Error fetching releases: " + e.getMessage());
            return;
        }
        
        if (releases.isEmpty()) {
            System.err.println("No releases found in repository");
            return;
        }

        // Sort releases by creation date
        releases.sort((r1, r2) -> {
            try {
                return r1.getCreatedAt().compareTo(r2.getCreatedAt());
            } catch (IOException e) {
                throw new RuntimeException("Error comparing release dates", e);
            }
        });

        // Filter releases based on start and end tags
        int startIndex = 0;
        int endIndex = releases.size() - 1;

        if (startRelease != null) {
            boolean found = false;
            for (int i = 0; i < releases.size(); i++) {
                if (releases.get(i).getTagName().equals(startRelease)) {
                    startIndex = i;
                    found = true;
                    break;
                }
            }
            if (!found) {
                System.err.println("Start release tag '" + startRelease + "' not found");
                return;
            }
        }

        if (endRelease != null) {
            boolean found = false;
            for (int i = 0; i < releases.size(); i++) {
                if (releases.get(i).getTagName().equals(endRelease)) {
                    endIndex = i;
                    found = true;
                    break;
                }
            }
            if (!found) {
                System.err.println("End release tag '" + endRelease + "' not found");
                return;
            }
        }

        if (startIndex > endIndex) {
            System.err.println("Start release is newer than end release");
            return;
        }

        // Subset the releases list
        releases = releases.subList(startIndex, endIndex + 1);

        Map<String, Duration> leadTimes = new HashMap<>();
        Map<String, List<PRData>> releasePRs = new HashMap<>();

        // Process each release
        for (int i = 0; i < releases.size(); i++) {
            GHRelease release = releases.get(i);
            Date previousReleaseDate = i > 0 ? releases.get(i-1).getCreatedAt() : null;
            
            // Get the commit associated with this release
            String releaseCommitSha = release.getTargetCommitish();
            
            // Find all PRs that were merged between the previous release and this release
            List<GHPullRequest> prs = repo.getPullRequests(GHIssueState.CLOSED);
            
            for (GHPullRequest pr : prs) {
                if (!pr.isMerged() || pr.getMergeCommitSha() == null) {
                    continue;
                }

                // Skip PRs merged to non-protected branches
                if (!isProtectedBranch(pr.getBase().getRef())) {
                    continue;
                }

                // Check if this PR's merge commit is included in this release
                if (isCommitInRelease(repo, pr.getMergeCommitSha(), releaseCommitSha)) {
                    Date mergeDate = pr.getMergedAt();
                    if (previousReleaseDate == null || mergeDate.after(previousReleaseDate)) {
                        Duration leadTime = Duration.between(
                            pr.getMergedAt().toInstant(),
                            release.getCreatedAt().toInstant()
                        );
                        
                        PRData prData = new PRData(
                            pr.getNumber(),
                            pr.getTitle(),
                            pr.getMergedAt(),
                            release.getCreatedAt(),
                            leadTime
                        );
                        
                        releasePRs.computeIfAbsent(release.getTagName(), k -> new ArrayList<>())
                                 .add(prData);
                    }
                }
            }
        }

        // Print results
        System.out.println("\nLead Time for Changes Report");
        System.out.println("===========================");
        
        if (startRelease != null || endRelease != null) {
            System.out.printf("Analyzing releases from %s to %s\n",
                startRelease != null ? startRelease : "first release",
                endRelease != null ? endRelease : "latest release");
            System.out.println("----------------------------------------");
        }
        
        releasePRs.forEach((release, prs) -> {
            System.out.printf("\nRelease: %s\n", release);
            System.out.println("----------------------------------------");
            
            if (prs.isEmpty()) {
                System.out.println("No PRs found for this release");
                return;
            }

            // Calculate average lead time for this release
            Duration avgLeadTime = prs.stream()
                .map(PRData::leadTime)
                .reduce(Duration.ZERO, Duration::plus)
                .dividedBy(prs.size());

            System.out.printf("Average Lead Time: %d days, %d hours\n", 
                avgLeadTime.toDays(), 
                avgLeadTime.toHoursPart());
            
            // Print details for each PR
            prs.forEach(pr -> {
                System.out.printf("PR #%d: %s\n", pr.number(), pr.title());
                System.out.printf("  Merged: %s\n", pr.mergeDate());
                System.out.printf("  Lead Time: %d days, %d hours\n",
                    pr.leadTime().toDays(),
                    pr.leadTime().toHoursPart());
            });
        });
    }

    private boolean isProtectedBranch(String branchName) {
        // Consider main, master, and develop as protected branches
        return branchName.equals("main") || 
               branchName.equals("master") || 
               branchName.equals("develop");
    }

    private boolean isCommitInRelease(GHRepository repo, String commitSha, String releaseSha) 
            throws IOException {
        // Use GitHub's compare API to check if the commit is an ancestor of the release
        GHCompare compare = repo.getCompare(commitSha, releaseSha);
        return compare.getStatus().equals("behind") || compare.getStatus().equals("identical");
    }
}

record PRData(int number, String title, Date mergeDate, Date releaseDate, Duration leadTime) {}
