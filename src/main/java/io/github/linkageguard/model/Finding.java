package io.github.linkageguard.model;

/**
 * subject = class that has the problem; target = what it references; origin = jar/dir of subject.
 */
public record Finding(
        Kind kind,
        Severity severity,
        String subject,
        String origin,
        String location,
        String target,
        String message) {

    /** Stable identity (no line numbers, no jar paths) used for baseline / before-after diff. */
    public String key() {
        return kind + "|" + subject + "|" + target;
    }
}
