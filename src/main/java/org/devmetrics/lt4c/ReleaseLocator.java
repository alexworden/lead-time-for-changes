package org.devmetrics.lt4c;

import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class ReleaseLocator {
    private static final Logger logger = LoggerFactory.getLogger(ReleaseLocator.class);
    private final GHRepository repository;

    public ReleaseLocator(GHRepository repository) {
        this.repository = repository;
    }

    public String findPreviousReleaseTag(String releaseTag) throws IOException {
        logger.debug("Finding previous release tag for: {}", releaseTag);
        
        // Normalize the target tag name by removing refs/tags/ prefix if present
        String normalizedReleaseTag = releaseTag.startsWith("refs/tags/") ? 
            releaseTag.substring("refs/tags/".length()) : releaseTag;
        String majorVersion = normalizedReleaseTag.startsWith("v") ? 
            normalizedReleaseTag.substring(1).split("\\.")[0] : 
            normalizedReleaseTag.split("\\.")[0];
            
        logger.debug("Looking for tags with major version: {}", majorVersion);
        List<String> allTags = new ArrayList<>();
        
        // Get all tags from GitHub
        for (GHRef ref : repository.listRefs("tags")) {
            String tagName = ref.getRef().substring("refs/tags/".length());
            // Only consider tags with same major version and proper format
            if ((tagName.startsWith("v" + majorVersion + ".") || tagName.startsWith(majorVersion + ".")) 
                && tagName.matches("v?\\d+\\.\\d+\\.\\d+")) {
                allTags.add(tagName);
                logger.debug("Added tag: {}", tagName);
            } else {
                logger.debug("Skipping tag {} as it doesn't match version format", tagName);
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
        
        logger.debug("Found {} matching tags with major version {}", allTags.size(), majorVersion);
        
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
