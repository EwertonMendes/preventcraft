package tblack.preventcraft.permissions;

import java.util.Set;
import java.util.UUID;

public record PermissionHolderSnapshot(String kind, String name, UUID uuid, Set<String> nodes) {
    public boolean isGroup() {
        return "GROUP".equalsIgnoreCase(kind);
    }

    public boolean isUser() {
        return "USER".equalsIgnoreCase(kind);
    }
}
