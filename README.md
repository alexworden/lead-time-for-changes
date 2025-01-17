# Lead Time for Changes Analyzer

A tool to analyze lead time for changes in Git repositories by examining pull requests and their time to release.

## Features

- Analyze lead time for changes between any two Git releases/tags
- Support for both local Git repositories and GitHub repositories
- Automatic repository cloning and caching for GitHub repositories
- Pull request detection from commit messages
- Detailed lead time calculation for each pull request
- Summary statistics for the analyzed time period
- Debug logging support for detailed analysis

## Usage

You can analyze a repository in two ways:

1. Using a local Git repository:
```bash
java -jar target/lead-time-for-changes-1.0-SNAPSHOT-jar-with-dependencies.jar \
  --directory /path/to/repo \
  -s v1.0.0 \
  -e v2.0.0
```

2. Using a GitHub repository URL:
```bash
java -jar target/lead-time-for-changes-1.0-SNAPSHOT-jar-with-dependencies.jar \
  --github-url https://github.com/owner/repo \
  -s v1.0.0 \
  -e v2.0.0
```

### Options

- `--directory` or `-D`: Path to local Git repository
- `--github-url` or `-g`: Full GitHub repository URL
- `-s` or `--start-release`: Start release tag/commit (defaults to end-release~1)
- `-e` or `--end-release`: End release tag/commit (defaults to HEAD)
- `-d` or `--debug`: Enable debug logging
- `-l` or `--limit`: Limit number of releases to analyze

### Examples

1. Analyze local repository between two tags:
```bash
java -jar target/lead-time-for-changes-1.0-SNAPSHOT-jar-with-dependencies.jar \
  --directory /path/to/repo \
  -s v1.0.0 \
  -e v2.0.0
```

2. Analyze GitHub repository between two tags:
```bash
java -jar target/lead-time-for-changes-1.0-SNAPSHOT-jar-with-dependencies.jar \
  --github-url https://github.com/intuit/auto \
  -s v11.0.5 \
  -e v11.1.0
```

3. Analyze with debug logging enabled:
```bash
java -jar target/lead-time-for-changes-1.0-SNAPSHOT-jar-with-dependencies.jar \
  --github-url https://github.com/intuit/auto \
  -s v11.0.5 \
  -e v11.1.0 \
  --debug
```

You can also enable debug logging by setting the environment variable:
```bash
export LOGBACK_LEVEL=DEBUG
```

## Authentication

For private repositories, you'll need to provide a Git access token. You can do this in two ways:

1. Via environment variable:
   ```bash
   export LT4C_GIT_TOKEN=your_token_here
   java -jar lead-time-analyzer.jar --github-url https://github.com/org/repo ...
   ```

2. Via command line argument:
   ```bash
   java -jar lead-time-analyzer.jar --github-url https://github.com/org/repo --token your_token_here ...
   ```

The token should have read access to the repository you want to analyze. For GitHub, you can create a Personal Access Token (PAT) in your account settings.

## Build

To build the project:
```bash
mvn clean package
```

This will create an executable jar with all dependencies in the `target` directory.

## Output Format

The tool provides:
1. Individual lead time for each detected pull request
2. Summary statistics including:
   - Average lead time
   - Median lead time
   - Total number of pull requests analyzed
   - Time period analyzed
