package uk.gov.moj.cp.ai.idempotency;

public enum GuardOutcome {
    /** The work ran under a freshly claimed lease. */
    EXECUTED,
    /** The status row is already terminal — the work was skipped entirely. */
    SKIPPED_TERMINAL
}
