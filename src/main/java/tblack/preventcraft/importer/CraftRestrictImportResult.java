package tblack.preventcraft.importer;

import java.nio.file.Path;
import java.util.List;

public record CraftRestrictImportResult(
        boolean success,
        boolean dryRun,
        int scannedHolders,
        int scannedNodes,
        int importedRules,
        int skippedDuplicates,
        int skippedWildcards,
        int skippedUnknown,
        List<String> unresolvedItemIds,
        int correctedItemTargets,
        String message,
        Path reportFile
) {
    public static CraftRestrictImportResult failure(boolean dryRun, String message) {
        return new CraftRestrictImportResult(false, dryRun, 0, 0, 0, 0, 0, 0, List.of(), 0, message, null);
    }
}
