package tblack.preventcraft.config;

public record ConfigOperationResult(
        boolean success,
        PreventCraftConfig config,
        String message
) {
    public static ConfigOperationResult success(PreventCraftConfig config, String message) {
        return new ConfigOperationResult(true, config, message);
    }

    public static ConfigOperationResult failure(PreventCraftConfig fallback, String message) {
        return new ConfigOperationResult(false, fallback, message);
    }
}
