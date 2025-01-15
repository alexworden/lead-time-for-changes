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

### Example

```bash
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r octocat/Hello-World
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
