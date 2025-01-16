package com.devmetrics;

import org.apache.commons.cli.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LeadTimeCalculator {
    private final String token;
    private final String repository;
    private final String startRelease;
    private final String endRelease;
    private final int limit;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Map<String, String> prCommitInfo;
    private String githubApiUrl = "https://api.github.com";
    private String githubUrl = "https://github.com";

    public LeadTimeCalculator(String token, String repository, String startRelease, String endRelease, int limit) {
        System.out.println("Initializing LeadTimeCalculator for " + repository);
        this.token = token;
        this.repository = repository;
        this.startRelease = startRelease;
        this.endRelease = endRelease;
        this.limit = limit;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = new ObjectMapper();
        this.prCommitInfo = new HashMap<>();
    }

    public void setGitHubUrl(String url) {
        this.githubUrl = url;
    }

    public void setGitHubApiUrl(String url) {
        this.githubApiUrl = url;
    }

    private HttpRequest.Builder createRequest(String url) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "LeadTimeCalculator"); // Add user agent as required by GitHub
        
        if (token != null && !token.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        
        return builder;
    }

    private void checkRateLimit() throws IOException, InterruptedException {
        var request = createRequest(githubApiUrl + "/rate_limit").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            var rateLimit = mapper.readTree(response.body()).get("resources").get("core");
            int remaining = rateLimit.get("remaining").asInt();
            long resetTime = rateLimit.get("reset").asLong();
            
            System.out.println("Rate limit status:");
            System.out.println("- Remaining: " + remaining);
            System.out.println("- Resets at: " + Instant.ofEpochSecond(resetTime));
            
            if (remaining < 10) {
                long waitTime = resetTime - Instant.now().getEpochSecond();
                if (waitTime > 0) {
                    System.out.println("Rate limit low. Please wait " + waitTime + " seconds or provide a GitHub token.");
                    throw new IOException("Rate limit exceeded");
                }
            }
        }
    }

    private String getOrCreateGitRepo() throws IOException, InterruptedException {
        // Create base directory for all repo clones if it doesn't exist
        Path baseDir = Paths.get(System.getProperty("user.home"), "LeadTimeForChanges_Temp_Git_Clones");
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
        }

        // Create a directory name based on the repository name (replace / with _)
        String repoDir = repository.replace('/', '_');
        Path repoPath = baseDir.resolve(repoDir);

        if (!Files.exists(repoPath)) {
            // Clone the repository if it doesn't exist
            String repoUrl = githubUrl + "/" + repository + ".git";
            System.out.println("Cloning repository for the first time...");
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--bare", repoUrl, repoPath.toString());
            Process p = pb.start();
            if (p.waitFor() != 0) {
                throw new IOException("Failed to clone repository");
            }
        } else {
            // Update existing repository
            System.out.println("Updating existing repository clone...");
            ProcessBuilder pb = new ProcessBuilder("git", "fetch", "--all");
            pb.directory(repoPath.toFile());
            Process p = pb.start();
            if (p.waitFor() != 0) {
                throw new IOException("Failed to update repository");
            }
        }

        return repoPath.toString();
    }

    private List<String> getPullRequestsFromGitLog(String repository, String fromTag, String toTag) throws IOException, InterruptedException {
        // Get or create the git repository
        String repoPath = getOrCreateGitRepo();
        
        System.out.println("Verifying tags...");
        ProcessBuilder pb = new ProcessBuilder("git", "tag", "-l", fromTag);
        pb.directory(Paths.get(repoPath).toFile());
        Process p = pb.start();
        boolean fromTagExists = p.waitFor() == 0 && !new String(p.getInputStream().readAllBytes()).trim().isEmpty();
        
        pb = new ProcessBuilder("git", "tag", "-l", toTag);
        pb.directory(Paths.get(repoPath).toFile());
        p = pb.start();
        boolean toTagExists = p.waitFor() == 0 && !new String(p.getInputStream().readAllBytes()).trim().isEmpty();

        if (!fromTagExists || !toTagExists) {
            System.out.println("Tag check: fromTag (" + fromTag + "): " + fromTagExists + 
                             ", toTag (" + toTag + "): " + toTagExists);
            throw new IOException("One or both tags not found");
        }

        // Get the commit hashes for both tags
        pb = new ProcessBuilder("git", "rev-parse", fromTag);
        pb.directory(Paths.get(repoPath).toFile());
        p = pb.start();
        String fromCommit = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0 || fromCommit.isEmpty()) {
            throw new IOException("Failed to get commit hash for " + fromTag);
        }

        pb = new ProcessBuilder("git", "rev-parse", toTag);
        pb.directory(Paths.get(repoPath).toFile());
        p = pb.start();
        String toCommit = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0 || toCommit.isEmpty()) {
            throw new IOException("Failed to get commit hash for " + toTag);
        }

        // Find the merge base (common ancestor) of the two tags
        pb = new ProcessBuilder("git", "merge-base", fromTag, toTag);
        pb.directory(Paths.get(repoPath).toFile());
        p = pb.start();
        String mergeBase = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0 || mergeBase.isEmpty()) {
            System.out.println("Warning: Could not find common ancestor between " + fromTag + " and " + toTag);
            mergeBase = null;
        } else {
            System.out.println("Found common ancestor: " + mergeBase);
        }

        System.out.println("Analyzing commits between " + fromTag + " (" + fromCommit + ") and " + 
                         toTag + " (" + toCommit + ")");

        List<String> mergeCommits = new ArrayList<>();

        // If we found a merge base and it's different from both commits
        if (mergeBase != null && !mergeBase.equals(fromCommit) && !mergeBase.equals(toCommit)) {
            // Get commits from merge-base to toTag
            pb = new ProcessBuilder(
                "git", "log",
                "--first-parent",
                "--merges",
                "--format=%H %aI %ae %s",
                mergeBase + ".." + toCommit
            );
            pb.directory(Paths.get(repoPath).toFile());
            p = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    mergeCommits.add(line);
                }
            }
            
            if (p.waitFor() != 0) {
                throw new IOException("Failed to get merge commits from merge-base to toTag");
            }

            // Get commits from merge-base to fromTag
            pb = new ProcessBuilder(
                "git", "log",
                "--first-parent",
                "--merges",
                "--format=%H %aI %ae %s",
                mergeBase + ".." + fromCommit
            );
            pb.directory(Paths.get(repoPath).toFile());
            p = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    mergeCommits.add(line);
                }
            }
            
            if (p.waitFor() != 0) {
                throw new IOException("Failed to get merge commits from merge-base to fromTag");
            }
        } else {
            // Direct path between commits (same branch)
            pb = new ProcessBuilder(
                "git", "log",
                "--first-parent",
                "--merges",
                "--format=%H %aI %ae %s",
                fromCommit + ".." + toCommit
            );
            pb.directory(Paths.get(repoPath).toFile());
            p = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    mergeCommits.add(line);
                }
            }
            
            if (p.waitFor() != 0) {
                throw new IOException("Failed to get merge commits");
            }
        }

        System.out.println("Found " + mergeCommits.size() + " merge commits");

        // Get PR numbers and merge commit info
        Set<String> prNumbers = new HashSet<>(); // Use Set to avoid duplicates
        
        for (String commit : mergeCommits) {
            String[] parts = commit.split(" ", 4);
            if (parts.length < 4) continue;
            
            String commitHash = parts[0];
            String commitDate = parts[1];
            String authorEmail = parts[2];
            String message = parts[3];
            
            // Look for different PR patterns:
            // 1. GitHub's standard "Merge pull request #X" in commit message
            Matcher matcher = Pattern.compile("Merge pull request #(\\d+)").matcher(message);
            if (matcher.find()) {
                String prNumber = matcher.group(1);
                prNumbers.add(prNumber);
                prCommitInfo.put(prNumber, commit);
                System.out.printf("Found PR #%s from commit %s by %s on %s: %s%n",
                    prNumber, commitHash.substring(0, 8), authorEmail, commitDate, message);
                continue;
            }
            
            // 2. "Merge PR #X" format
            matcher = Pattern.compile("Merge PR #(\\d+)").matcher(message);
            if (matcher.find()) {
                String prNumber = matcher.group(1);
                prNumbers.add(prNumber);
                prCommitInfo.put(prNumber, commit);
                System.out.printf("Found PR #%s from commit %s by %s on %s: %s%n",
                    prNumber, commitHash.substring(0, 8), authorEmail, commitDate, message);
                continue;
            }
            
            // 3. Look for PR references in the patch itself (e.g. "cherry-picked from PR #X")
            matcher = Pattern.compile("(?:PR|pull request) #(\\d+)").matcher(message);
            while (matcher.find()) {
                String prNumber = matcher.group(1);
                prNumbers.add(prNumber);
                prCommitInfo.put(prNumber, commit);
                System.out.printf("Found PR #%s from commit %s by %s on %s: %s%n",
                    prNumber, commitHash.substring(0, 8), authorEmail, commitDate, message);
            }
        }

        System.out.println("Found " + prNumbers.size() + " merged pull requests");

        List<String> sortedPRs = new ArrayList<>(prNumbers);
        Collections.sort(sortedPRs, (a, b) -> Integer.parseInt(a) - Integer.parseInt(b));
        return sortedPRs;
    }

    private static class PullRequest {
        final String number;
        final String title;
        final String author;
        final String authorEmail;
        final String commitHash;
        final Instant mergedAt;
        final String message;

        PullRequest(String number, String title, String author, String authorEmail, 
                   String commitHash, Instant mergedAt, String message) {
            this.number = number;
            this.title = title;
            this.author = author;
            this.authorEmail = authorEmail;
            this.commitHash = commitHash;
            this.mergedAt = mergedAt;
            this.message = message;
        }
    }

    private void printPRDetails(PullRequest pr, Instant releaseDate) {
        Duration leadTime = Duration.between(pr.mergedAt, releaseDate);
        System.out.printf("  PR #%-5s by %s (%s)%n", pr.number, pr.author, pr.authorEmail);
        System.out.printf("    Commit:    %s%n", pr.commitHash);
        System.out.printf("    Merged:    %s%n", pr.mergedAt);
        System.out.printf("    Message:   %s%n", pr.message);
        System.out.printf("    Lead Time: %d days%n", leadTime.toDays());
    }

    private void printStatistics(List<Duration> leadTimes, String title) {
        if (leadTimes.isEmpty()) {
            System.out.println(title + ": No data");
            return;
        }

        // Sort for percentile calculations
        Collections.sort(leadTimes);
        
        // Calculate statistics
        Duration mean = leadTimes.stream()
            .reduce(Duration.ZERO, Duration::plus)
            .dividedBy(leadTimes.size());
        
        Duration median = leadTimes.get(leadTimes.size() / 2);
        
        Duration p90 = leadTimes.get((int)(leadTimes.size() * 0.9));
        
        System.out.printf("%s:%n", title);
        System.out.printf("  Mean:   %d days%n", mean.toDays());
        System.out.printf("  Median: %d days%n", median.toDays());
        System.out.printf("  P90:    %d days%n", p90.toDays());
    }

    public void calculateLeadTimes() throws IOException, InterruptedException {
        System.out.println("Calculating lead times for " + repository);
        
        try {
            // Check rate limit first
            checkRateLimit();
            
            // Get releases with pagination and limit
            var releasesUrl = String.format("%s/repos/%s/releases?per_page=%d", githubApiUrl, repository, limit);
            var request = createRequest(releasesUrl).GET().build();
            
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to fetch releases: " + response.body());
            }
            
            var releases = mapper.readTree(response.body());
            System.out.println("Found " + releases.size() + " releases (limited to " + limit + ")");
            
            if (releases.size() == 0) {
                System.err.println("No releases found in repository");
                return;
            }

            // Sort releases by creation date
            List<JsonNode> releasesList = new ArrayList<>();
            releases.forEach(releasesList::add);
            releasesList.sort((r1, r2) -> {
                String date1 = r1.get("created_at").asText();
                String date2 = r2.get("created_at").asText();
                return date1.compareTo(date2);
            });

            // Filter by release tags if specified
            int startIndex = -1;
            int endIndex = -1;

            if (startRelease != null) {
                for (int i = 0; i < releasesList.size(); i++) {
                    if (releasesList.get(i).get("tag_name").asText().equals(startRelease)) {
                        startIndex = i;
                        break;
                    }
                }
                if (startIndex == -1) {
                    throw new IOException("Start release tag '" + startRelease + "' not found");
                }
            } else {
                startIndex = 0;
            }

            if (endRelease != null) {
                for (int i = 0; i < releasesList.size(); i++) {
                    if (releasesList.get(i).get("tag_name").asText().equals(endRelease)) {
                        endIndex = i;
                        break;
                    }
                }
                if (endIndex == -1) {
                    throw new IOException("End release tag '" + endRelease + "' not found");
                }
            } else {
                endIndex = releasesList.size() - 1;
            }

            if (startIndex > endIndex) {
                throw new IOException("Start release is newer than end release");
            }

            releasesList = releasesList.subList(startIndex, endIndex + 1);
            System.out.println("\nAnalyzing releases from " + 
                releasesList.get(0).get("tag_name").asText() + " to " + 
                releasesList.get(releasesList.size() - 1).get("tag_name").asText() + "\n");

            // Process each release
            for (int i = 0; i < releasesList.size(); i++) {
                JsonNode release = releasesList.get(i);
                String tagName = release.get("tag_name").asText();
                Instant releaseDate = Instant.parse(release.get("created_at").asText());
                System.out.printf("%nProcessing release: %s (created at %s)%n", 
                    tagName, releaseDate);
                
                if (i == 0) {
                    System.out.println("Skipping first release (no previous release to compare with)\n");
                    continue;
                }

                // Get PRs between this release and the previous one
                String previousTag = releasesList.get(i-1).get("tag_name").asText();
                List<String> prNumbers = getPullRequestsFromGitLog(repository, previousTag, tagName);
                System.out.println("Found " + prNumbers.size() + " merged pull requests");

                if (prNumbers.isEmpty()) {
                    System.out.println("No pull requests to analyze\n");
                    continue;
                }

                // Get PR details and calculate lead times
                Map<String, PullRequest> prs = new HashMap<>();
                for (String prNumber : prNumbers) {
                    try {
                        var prUrl = String.format("%s/repos/%s/pulls/%s", githubApiUrl, repository, prNumber);
                        var prRequest = createRequest(prUrl).GET().build();
                        var prResponse = client.send(prRequest, HttpResponse.BodyHandlers.ofString());
                        
                        if (prResponse.statusCode() == 404) {
                            // PR was deleted or is not accessible, try the issues API
                            prUrl = String.format("%s/repos/%s/issues/%s", githubApiUrl, repository, prNumber);
                            prRequest = createRequest(prUrl).GET().build();
                            prResponse = client.send(prRequest, HttpResponse.BodyHandlers.ofString());
                        }
                        
                        if (prResponse.statusCode() != 200) {
                            System.err.println("Failed to fetch PR #" + prNumber + ": " + prResponse.body());
                            continue;
                        }
                        
                        var pr = mapper.readTree(prResponse.body());
                        String title = pr.has("title") ? pr.get("title").asText() : "Unknown";
                        String author = pr.has("user") && !pr.get("user").isNull() ? 
                            pr.get("user").get("login").asText() : "Unknown";
                        
                        // Get commit info
                        String commitInfo = prCommitInfo.get(prNumber);
                        if (commitInfo != null) {
                            String[] parts = commitInfo.split(" ", 4);
                            String commitHash = parts[0];
                            String commitDate = parts[1];
                            String authorEmail = parts[2];
                            String message = parts[3];
                            
                            prs.put(prNumber, new PullRequest(
                                prNumber,
                                title,
                                author,
                                authorEmail,
                                commitHash.substring(0, 8),
                                Instant.parse(commitDate),
                                message
                            ));
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing PR #" + prNumber + ": " + e.getMessage());
                    }
                }

                if (prs.isEmpty()) {
                    System.out.println("No merged pull requests found\n");
                    continue;
                }

                System.out.println("\nPull Request Details:");
                prs.values().stream()
                   .sorted((a, b) -> a.mergedAt.compareTo(b.mergedAt))
                   .forEach(pr -> printPRDetails(pr, releaseDate));

                // Calculate statistics
                List<Duration> leadTimes = prs.values().stream()
                    .map(pr -> Duration.between(pr.mergedAt, releaseDate))
                    .collect(Collectors.toList());

                System.out.println("\nLead Time Statistics:");
                printStatistics(leadTimes, "Overall");
                System.out.println();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted", e);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Lead Time Calculator...");
        Options options = new Options();

        options.addOption("t", "token", true, "GitHub Personal Access Token");
        options.addOption("r", "repository", true, "Repository in format owner/repository");
        options.addOption("s", "start-release", true, "Start release tag");
        options.addOption("e", "end-release", true, "End release tag");
        options.addOption("l", "limit", true, "Limit number of releases to analyze");
        options.addOption("u", "github-url", true, "GitHub URL (e.g., https://github.mycompany.com)");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (!cmd.hasOption("r")) {
                formatter.printHelp("LeadTimeCalculator", options);
                System.exit(1);
            }

            String token = cmd.getOptionValue("t");
            String repository = cmd.getOptionValue("r");
            String startRelease = cmd.getOptionValue("s");
            String endRelease = cmd.getOptionValue("e");
            int limit = cmd.hasOption("l") ? Integer.parseInt(cmd.getOptionValue("l")) : Integer.MAX_VALUE;
            String githubUrl = cmd.getOptionValue("u", "https://github.com");

            System.out.println("Starting Lead Time Calculator...");
            LeadTimeCalculator calculator = new LeadTimeCalculator(token, repository, startRelease, endRelease, limit);
            
            // Set custom GitHub URL if provided
            if (cmd.hasOption("u")) {
                calculator.setGitHubUrl(githubUrl);
                calculator.setGitHubApiUrl(githubUrl.replace("github", "api.github"));
            }
            
            try {
                calculator.calculateLeadTimes();
            } catch (IOException | InterruptedException e) {
                System.err.println("An error occurred: " + e.getMessage());
                System.exit(1);
            }
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("LeadTimeCalculator", options);
            System.exit(1);
        }
    }
}
