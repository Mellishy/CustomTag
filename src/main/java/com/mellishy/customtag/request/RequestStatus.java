package com.mellishy.customtag.request;

/**
 * The single, closed set of states a tag request may ever be in - no ad-hoc statuses may be
 * invented anywhere else in the project. Transitions are managed exclusively by
 * {@link RequestManager}; every transition is recorded in the request's history.
 */
public enum RequestStatus {

    /** Waiting in the global pending queue for AI or staff review. */
    PENDING,
    /** Currently being processed (an AI call or staff action is in flight). */
    PROCESSING,
    /** AI could not decide confidently - escalated to the staff review queue. */
    AI_REVIEW,
    /** Locked by a staff member for exclusive handling (still open). */
    LOCKED,
    /** Accepted - the tag is live. */
    APPROVED,
    /** Denied with a reason. */
    REJECTED,
    /** Denied AND the token was returned. */
    REFUNDED,
    /** Withdrawn by the player before a decision. */
    CANCELLED,
    /** A previously-decided request that staff reopened for another look. */
    REOPENED,
    /** Silently removed by staff (no player notification). */
    REMOVED,
    /** Sat in the queue longer than the configured expiry and was closed automatically. */
    EXPIRED;

    /** True while the request still needs a decision (occupies a pending-queue slot). */
    public boolean isOpen() {
        return this == PENDING || this == PROCESSING || this == AI_REVIEW || this == LOCKED || this == REOPENED;
    }

    /** True once a final decision exists (approve/reject family). */
    public boolean isDecided() {
        return this == APPROVED || this == REJECTED || this == REFUNDED;
    }
}
