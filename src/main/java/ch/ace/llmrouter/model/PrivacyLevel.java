package ch.ace.llmrouter.model;

/** Privacy levels from the annotation guideline (annotation_pilot). */
public enum PrivacyLevel {
    /** No personal data, nothing confidential. */
    D0,
    /** Personal data. */
    D1,
    /** Special categories of personal data. */
    D2,
    /** Confidential business information without personal data. */
    D3
}
