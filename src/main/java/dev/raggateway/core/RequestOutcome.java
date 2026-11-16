package dev.raggateway.core;

/** Final state of a request, written to the request log {@code status} column. */
public enum RequestOutcome {
    SUCCESS("ok"), ERROR("error"), CLIENT_CANCELLED("client_cancelled");

    private final String logValue;

    RequestOutcome(String logValue) {
        this.logValue = logValue;
    }

    public String logValue() {
        return logValue;
    }
}
