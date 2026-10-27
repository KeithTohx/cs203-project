package csd.tripsense.user;

/** Account business rules, independent of the HTTP transport. */
public class AccountRuleException extends RuntimeException {
    public enum Kind { INVALID_INPUT, CONFLICT }
    private final Kind kind;

    public AccountRuleException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
