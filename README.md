# Lead Time Calculator

This command-line utility calculates the Lead Time for Changes (LTC) metric for GitHub repositories. Two versions are available:

- **LeadTimeCalculator (v1)**: Measures lead time from when a pull request is merged until it is released.
- **LeadTimeCalculatorv2 (v2)**: Measures lead time from the first commit in a pull request until it is released, providing more accurate metrics.

## Features

### Version 1 Features
- Calculates lead time from PR merge to release
- Uses local git operations for efficient PR analysis
- Maintains persistent repository clones
- Provides basic per-release metrics
- Shows mean lead time statistics

### Version 2 Features
- Calculates lead time from first commit to release
- Uses GitHub API for accurate PR analysis
- Provides detailed per-release metrics including:
  - PR title and author
  - First commit date
  - Merge date
  - Base branch information
- Shows statistical analysis including mean and median
- Better handles complex git histories

## Prerequisites

- Java 17 or higher
- Maven
- Git command-line tool
- GitHub Personal Access Token with repo scope (optional for public repositories)

## Building

```bash
mvn clean package
```

This will create executable JARs with all dependencies included:
- `target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar` (v1)
- `target/lead-time-calculatorv2-1.0-SNAPSHOT-jar-with-dependencies.jar` (v2)

## Usage

Both versions use the same command-line interface:

```bash
# Version 1
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t <github-token> \
    -r <owner/repository> \
    [-s <start-release>] \
    [-e <end-release>] \
    [-l <limit>] \
    [-u <github-url>] \
    [-d <debug>]

# Version 2
java -jar target/lead-time-calculatorv2-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t <github-token> \
    -r <owner/repository> \
    [-s <start-release>] \
    [-e <end-release>] \
    [-l <limit>] \
    [-u <github-url>] \
    [-d <debug>]
```

### Arguments

- `-t, --token`: GitHub Personal Access Token (optional for public repositories)
- `-r, --repository`: The repository to analyze in the format `owner/repository`
- `-s, --start-release`: (Optional) Start release tag to analyze from
- `-e, --end-release`: (Optional) End release tag to analyze until
- `-l, --limit`: (Optional) Limit the number of releases to analyze
- `-u, --github-url`: (Optional) Custom GitHub URL for enterprise installations
- `-d, --debug`: (Optional) Enable debug logging

### Examples

```bash
# Version 1: Analyze the latest 3 releases
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -r spring-projects/spring-framework \
    -l 3

# Version 2: Analyze specific releases with more detailed output
java -jar target/lead-time-calculatorv2-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r spring-projects/spring-framework \
    -s v6.1.0 \
    -e v6.1.1

# Version 1: Analyze with debug logging enabled
java -jar target/lead-time-calculator-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -r spring-projects/spring-framework \
    -l 3 \
    -d

# Version 2: Analyze with debug logging enabled
java -jar target/lead-time-calculatorv2-1.0-SNAPSHOT-jar-with-dependencies.jar \
    -t ghp_your_token_here \
    -r spring-projects/spring-framework \
    -s v6.1.0 \
    -e v6.1.1 \
    -d
```

You can also enable debug logging by setting the environment variable:
```bash
export LOGBACK_LEVEL=DEBUG
```

## Output Format

### Version 1 Output
```
Processing release: v6.2.1
PR #33891:
  Author:    youabledev
  Branch:    main
  Lead Time: 2 days

Average lead time: 15 days
```

### Version 2 Output
```
Release: v6.2.1
Created at: 2024-12-12T09:18:03Z

PR #33891
  Title:       Fix bug in authentication flow
  Author:      youabledev
  Base Branch: main
  First Commit: 2024-12-08T15:30:00Z
  Merged At:    2024-12-10T07:47:52Z
  Lead Time:    1.7 days

Release Statistics:
  Total PRs:         15
  Average Lead Time: 2.3 days
  Median Lead Time:  1.8 days
```

## Implementation Details

### Version 1
- Uses local git operations
- Calculates lead time from merge to release
- Maintains persistent repository clones
- Basic statistical analysis

### Version 2
- Uses GitHub API for accurate data
- Calculates lead time from first commit
- Provides more detailed PR information
- Advanced statistical analysis
- Better handles complex workflows

## Notes

- Version 2 provides more accurate lead time measurements by considering the first commit
- Version 2 requires more GitHub API calls but provides better data
- Both versions support the same command-line interface
- Choose Version 1 for basic metrics or Version 2 for detailed analysis
