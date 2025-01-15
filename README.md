# Lead Time Calculator

This command-line utility calculates the Lead Time for Changes (LTC) metric for GitHub repositories. Lead Time is measured from when a pull request is merged until it is released.

## Features

- Calculates lead time for all changes in GitHub releases
- Only considers changes merged to protected branches (main, master, develop)
- Provides detailed per-release metrics
- Shows average lead time per release
- Detailed breakdown of each PR's lead time

## Prerequisites

- Java 17 or higher
- Maven
- GitHub Personal Access Token with repo scope

## Building

```bash
mvn clean package
```

This will create an executable JAR with all dependencies included at `target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar`

## Usage

```bash
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t <github-token> \
    -r <owner/repository>
```

### Arguments

- `-t, --token`: Your GitHub Personal Access Token
- `-r, --repository`: The repository to analyze in the format `owner/repository`
- `-s, --start-release`: (Optional) Start release tag to analyze from
- `-e, --end-release`: (Optional) End release tag to analyze until

If neither start nor end release is specified, all releases will be analyzed.
If only start release is specified, analysis will be from that release to the latest.
If only end release is specified, analysis will be from the first release to the specified release.
If both are specified, analysis will be limited to releases between them, inclusive.

### Example

```bash
# Analyze all releases
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r octocat/Hello-World

# Analyze a specific release
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r octocat/Hello-World \
    -s v1.0.0 \
    -e v1.0.0

# Analyze releases between v1.0.0 and v2.0.0
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r octocat/Hello-World \
    -s v1.0.0 \
    -e v2.0.0
```

## Output

The tool will output a report showing:
- Each release and its associated PRs
- Average lead time per release
- Individual PR lead times
- Merge and release dates for each PR

## Notes

- Only considers PRs merged to protected branches (main, master, develop)
- Lead time is calculated from PR merge time to release time
- Releases must be tagged in GitHub for proper calculation
