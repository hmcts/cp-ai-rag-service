package uk.gov.moj.cp.ai.idempotency;

@FunctionalInterface
public interface IdempotentWork {

    void run(ClaimToken token) throws Exception;
}
