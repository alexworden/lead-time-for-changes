# Lead Time for Changes Analyzer

A tool to analyze lead time for changes in Git repositories by examining pull requests and their time to release.

## Requirements

- Java 17 or higher
- Maven 3.6 or higher
- Git

## Features

- Analyze lead time for changes between any two Git releases/tags
- Support for both local Git repositories and GitHub repositories
- Automatic repository cloning and caching for GitHub repositories
- Pull request detection from commit messages
- Detailed lead time calculation for each pull request
- Summary statistics for the analyzed time period
- Debug logging support for detailed analysis

## Build

To build the project:

1. Clone the repository:
```bash
git clone <repository-url>
cd lead-time-for-changes
```

2. Build with Maven:
```bash
mvn clean package
```

This will create an executable jar with all dependencies at `target/LT4C-1.1.0-SNAPSHOT-jar-with-dependencies.jar`

## Usage

You can analyze a repository in two ways:

1. Using a local Git repository:
```bash
java -jar target/LT4C-1.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --directory /path/to/repo \
  --from-release v1.0.0 \
  --target-release v2.0.0
```

2. Using a GitHub repository URL:
```bash
java -jar target/LT4C-1.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --github-url https://github.com/owner/repo \
  --target-release v2.0.0
```

### Options

- `-d` or `--directory`: Path to local Git repository
- `-u` or `--github-url`: GitHub repository URL
- `-t` or `--token`: GitHub token (can also be set via `LT4C_GIT_TOKEN` environment variable)
- `-fr` or `--from-release`: Starting release tag/commit (optional)
- `-tr` or `--target-release`: Target release tag/commit (required)
- `-l` or `--limit`: Limit number of releases to analyze
- `-g` or `--debug`: Enable debug logging

### Examples

1. Analyze local repository between two releases:
```bash
java -jar target/LT4C-1.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --directory /path/to/repo \
  --from-release v1.0.0 \
  --target-release v2.0.0
```

2. Analyze GitHub repository with authentication:
```bash
export LT4C_GIT_TOKEN=your_github_token
java -jar target/LT4C-1.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --github-url https://github.com/owner/repo \
  --target-release v2.0.0
```

3. Analyze with debug logging enabled:
```bash
java -jar target/LT4C-1.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --github-url https://github.com/owner/repo \
  --target-release v2.0.0 \
  --debug
```

You can also enable debug logging by setting the environment variable:
```bash
export LOGBACK_LEVEL=DEBUG
```

## Output Format

The tool provides:
1. Individual lead time for each detected pull request
2. Summary statistics including:
   - Average lead time
   - Median lead time
   - Total number of pull requests analyzed
   - Time period analyzed
