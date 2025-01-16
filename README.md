# Lead Time Calculator

This command-line utility calculates the Lead Time for Changes (LTC) metric for GitHub repositories. Lead Time is measured from when a pull request is merged until it is released.

## Features

- Calculates lead time for all changes in GitHub releases
- Uses local git operations for efficient PR analysis
- Maintains persistent repository clones to avoid repeated downloads
- Provides detailed per-release metrics including:
  - PR author and email
  - Merge commit hash and date
  - Complete merge commit message
  - Lead time in days
- Shows statistical analysis including mean, median, and 90th percentile
- Handles complex git histories with multiple branches and merge bases

## Prerequisites

- Java 17 or higher
- Maven
- Git command-line tool
- GitHub Personal Access Token with repo scope (optional for public repositories)

## Building

```bash
mvn clean package
```

This will create an executable JAR with all dependencies included at `target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar`

## Usage

```bash
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t <github-token> \
    -r <owner/repository> \
    [-s <start-release>] \
    [-e <end-release>] \
    [-l <limit>] \
    [-u <github-url>]
```

### Arguments

- `-t, --token`: GitHub Personal Access Token (optional for public repositories)
- `-r, --repository`: The repository to analyze in the format `owner/repository`
- `-s, --start-release`: (Optional) Start release tag to analyze from
- `-e, --end-release`: (Optional) End release tag to analyze until
- `-l, --limit`: (Optional) Limit the number of releases to analyze
- `-u, --github-url`: (Optional) Custom GitHub URL for enterprise installations (e.g., https://github.mycompany.com)

If neither start nor end release is specified, all releases will be analyzed.
If only start release is specified, analysis will be from that release to the latest.
If only end release is specified, analysis will be from the first release to the specified release.
If both are specified, analysis will be limited to releases between them, inclusive.

Note: While the GitHub token is optional for public repositories, using a token increases the API rate limit from 60 to 5000 requests per hour.

### Examples

```bash
# Analyze the latest 3 releases in a public repository
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -r spring-projects/spring-framework \
    -l 3

# Analyze specific releases with a token
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r spring-projects/spring-framework \
    -s v6.1.0 \
    -e v6.1.1

# Analyze all releases with a token
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r spring-projects/spring-framework

# Analyze repository on enterprise GitHub
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r myteam/myproject \
    -u https://github.mycompany.com
```

## Output

The tool outputs a detailed report showing:

1. Release Information:
   - Release tag and creation date
   - Number of PRs included

2. Pull Request Details:
   - PR number and title
   - Author's GitHub username and email
   - Merge commit hash
   - Merge date and commit message
   - Branch
   - Lead time in days

3. Statistical Analysis:
   - Mean lead time
   - Median lead time
   - 90th percentile lead time

Example output:
```
Processing release: v6.2.1 (created at 2024-12-12T09:18:03Z)

Pull Request Details:
  PR #33891 by youabledev (author@example.com)
    Commit:    4f815b00
    Merged:    2024-12-10T07:47:52+01:00
    Message:   Merge pull request #33891 from youabledev
    Branch:    main
    Lead Time: 2 days

Lead Time Statistics:
  Mean:   15 days
  Median: 12 days
  P90:    30 days
```

## Implementation Details

1. **Repository Management**:
   - Creates a persistent bare clone in `~/LeadTimeForChanges_Temp_Git_Clones/<owner>_<repo>`
   - Updates existing clones with `git fetch --all` on subsequent runs
   - Avoids repeated downloads of the same repository

2. **Git Analysis**:
   - Uses `git merge-base` to find common ancestors between releases
   - Analyzes merge commits to identify merged PRs
   - Extracts detailed information from commit messages

3. **Performance Optimization**:
   - Minimizes GitHub API calls by using local git operations
   - Maintains persistent repository clones
   - Uses bare clones to reduce disk usage

## Notes

- The tool creates persistent git clones in your home directory under `LeadTimeForChanges_Temp_Git_Clones`
- Only considers merge commits to identify PRs
- Lead time is calculated from PR merge time to release time
- Releases must be tagged in GitHub for proper calculation
