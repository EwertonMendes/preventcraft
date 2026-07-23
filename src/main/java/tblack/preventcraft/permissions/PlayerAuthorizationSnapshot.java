package tblack.preventcraft.permissions;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Immutable authorization data safe to read from gameplay systems without I/O. */
public record PlayerAuthorizationSnapshot(
        UUID uuid,
        String username,
        Set<String> groups,
        boolean bypassAll,
        boolean bypassCraft,
        boolean bypassBench,
        boolean ready,
        long version
) {
    public PlayerAuthorizationSnapshot {
        username = username == null ? "" : username;
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    public static PlayerAuthorizationSnapshot pending(UUID uuid, String username) {
        return new PlayerAuthorizationSnapshot(uuid, username, Set.of(), false, false, false, false, 0L);
    }

    public boolean belongsTo(String group) {
        return group != null && groups.contains(group.trim().toLowerCase(Locale.ROOT));
    }
}
