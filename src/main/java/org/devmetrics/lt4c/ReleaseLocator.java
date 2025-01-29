package org.devmetrics.lt4c;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class ReleaseLocator {
    private static final Logger logger = LoggerFactory.getLogger(ReleaseLocator.class);
    private final Git git;

    public ReleaseLocator(Git git) {
        this.git = git;
    }

    public String findPreviousReleaseTag(String releaseTag) throws IOException, GitAPIException {
        logger.info("Finding previous release tag for: {}", releaseTag);
        
        Repository repo = git.getRepository();
        Ref targetRef = repo.findRef(releaseTag);
        if (targetRef == null) {
            targetRef = repo.findRef("refs/tags/" + releaseTag);
        }
        
        if (targetRef == null) {
            logger.warn("Could not find tag: {}", releaseTag);
            return null;
        }

        // Create a map of commit hash to tag name for all tags
        Map<String, String> commitToTag = new HashMap<>();
        // Normalize the target tag name by removing refs/tags/ prefix if present
        String normalizedReleaseTag = releaseTag.startsWith("refs/tags/") ? 
            releaseTag.substring("refs/tags/".length()) : releaseTag;
        String majorVersion = normalizedReleaseTag.startsWith("v") ? 
            normalizedReleaseTag.substring(1).split("\\.")[0] : 
            normalizedReleaseTag.split("\\.")[0];
            
        logger.info("Looking for tags with major version: {}", majorVersion);
        List<String> allTags = new ArrayList<>();
        
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : git.tagList().call()) {
                String tagName = ref.getName().substring(ref.getName().lastIndexOf("/") + 1);
                // Only consider tags with same major version and proper format
                if ((tagName.startsWith("v" + majorVersion + ".") || tagName.startsWith(majorVersion + ".")) 
                    && tagName.matches("v?\\d+\\.\\d+\\.\\d+")) {
                    // Peel the tag to get the actual commit it points to
                    RevObject obj = walk.parseAny(ref.getObjectId());
                    while (obj instanceof RevTag) {
                        obj = walk.parseAny(((RevTag) obj).getObject());
                    }
                    if (obj instanceof RevCommit) {
                        RevCommit commit = (RevCommit) obj;
                        String commitHash = commit.getName();
                        commitToTag.put(commitHash, tagName);
                        allTags.add(tagName);
                        logger.debug("Added tag: {} at commit: {} with message: {}", 
                            tagName, commitHash.substring(0, 8), commit.getShortMessage());
                    }
                } else {
                    logger.debug("Skipping tag {} as it doesn't match version format", tagName);
                }
            }
        }
        
        // Sort tags in reverse order (newest to oldest)
        Collections.sort(allTags, (a, b) -> {
            String[] partsA = a.replaceAll("^v", "").split("\\.");
            String[] partsB = b.replaceAll("^v", "").split("\\.");
            for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
                int compareResult = Integer.compare(
                    Integer.parseInt(partsA[i]), 
                    Integer.parseInt(partsB[i]));
                if (compareResult != 0) {
                    return -compareResult; // Reverse order
                }
            }
            return -Integer.compare(partsA.length, partsB.length);
        });
        
        logger.info("Found {} matching tags with major version {}", allTags.size(), majorVersion);
        
        // Find the previous tag by looking at sorted list
        String previousTag = null;
        boolean foundCurrentTag = false;
        for (String tag : allTags) {
            if (tag.equals(normalizedReleaseTag)) {
                foundCurrentTag = true;
                logger.debug("Found current tag: {}", tag);
            } else if (foundCurrentTag) {
                previousTag = tag;
                logger.debug("Found previous tag: {}", tag);
                break;
            }
        }

        if (previousTag == null) {
            logger.warn("No previous release tag found for: {}", releaseTag);
        } else {
            logger.debug("Found previous tag for {} is tag: {}", releaseTag, previousTag);
        }
        return previousTag;
    }
}
