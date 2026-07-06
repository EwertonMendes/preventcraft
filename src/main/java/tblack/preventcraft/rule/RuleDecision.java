package tblack.preventcraft.rule;

public record RuleDecision(boolean allowed, PreventRule rule, String reason) {
    public static RuleDecision allowed(String reason) {
        return new RuleDecision(true, null, reason);
    }

    public static RuleDecision denied(PreventRule rule, String reason) {
        return new RuleDecision(false, rule, reason);
    }
}
