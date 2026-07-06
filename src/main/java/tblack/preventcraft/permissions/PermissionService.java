package tblack.preventcraft.permissions;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PermissionService {
    private static final long CACHE_TTL_MILLIS = 5000L;

    private final Map<CacheKey, CachedPermission> permissionCache = new ConcurrentHashMap<>();
    private final Map<UUID, CachedGroups> groupCache = new ConcurrentHashMap<>();
    private boolean luckPermsChecked;
    private Object luckPermsApi;

    public void register(String permission) {
        if (permission == null || permission.isBlank()) return;
        try {
            PermissionsModule.get().registerPermission(permission);
        } catch (Throwable ignored) {
        }
    }

    public boolean hasPermission(UUID uuid, String permission) {
        if (uuid == null || permission == null || permission.isBlank()) return false;
        CacheKey key = new CacheKey(uuid, permission.toLowerCase(Locale.ROOT));
        CachedPermission cached = permissionCache.get(key);
        if (cached != null && cached.isValid()) return cached.allowed;
        boolean allowed = hasNativePermission(uuid, permission)
                || hasNativePermission(uuid, "*")
                || hasLuckPermsPermission(uuid, permission)
                || hasLuckPermsPermission(uuid, "*");
        permissionCache.put(key, new CachedPermission(allowed, System.currentTimeMillis()));
        return allowed;
    }

    public Set<String> getGroups(PlayerRef playerRef) {
        if (playerRef == null) return Set.of();
        UUID uuid;
        try {
            uuid = playerRef.getUuid();
        } catch (Throwable ignored) {
            return Set.of();
        }
        CachedGroups cached = groupCache.get(uuid);
        if (cached != null && cached.isValid()) return cached.groups;
        Set<String> groups = new LinkedHashSet<>();
        try {
            Object user = loadLuckPermsUser(uuid);
            if (user != null) {
                Object primary = call(user, "getPrimaryGroup");
                addGroup(groups, primary);
                addGroupsFromNodes(groups, call(user, "getNodes"));
            }
        } catch (Throwable ignored) {
        }
        Set<String> immutable = Set.copyOf(groups);
        groupCache.put(uuid, new CachedGroups(immutable, System.currentTimeMillis()));
        return immutable;
    }

    public boolean isLuckPermsAvailable() {
        return getLuckPermsApi() != null;
    }

    public List<PermissionHolderSnapshot> readLuckPermsSnapshots(boolean includeUsers) {
        Object api = getLuckPermsApi();
        if (api == null) return List.of();
        List<PermissionHolderSnapshot> snapshots = new ArrayList<>();
        snapshots.addAll(readGroups(api));
        if (includeUsers) snapshots.addAll(readUsers(api));
        return snapshots;
    }

    public void clearCache() {
        permissionCache.clear();
        groupCache.clear();
        luckPermsChecked = false;
        luckPermsApi = null;
    }

    private boolean hasNativePermission(UUID uuid, String permission) {
        try {
            return PermissionsModule.get().hasPermission(uuid, permission);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasLuckPermsPermission(UUID uuid, String permission) {
        try {
            Object user = loadLuckPermsUser(uuid);
            if (user == null) return false;
            Object cachedData = call(user, "getCachedData");
            Object permissionData = call(cachedData, "getPermissionData");
            Object result = call(permissionData, "checkPermission", String.class, permission);
            Object bool = call(result, "asBoolean");
            return Boolean.TRUE.equals(bool);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object loadLuckPermsUser(UUID uuid) throws Exception {
        Object api = getLuckPermsApi();
        if (api == null) return null;
        Object userManager = call(api, "getUserManager");
        Object user = call(userManager, "getUser", UUID.class, uuid);
        if (user != null) return user;
        Object future = call(userManager, "loadUser", UUID.class, uuid);
        if (future instanceof CompletableFuture<?> completableFuture) return completableFuture.getNow(null);
        return null;
    }

    private Object getLuckPermsApi() {
        if (luckPermsChecked) return luckPermsApi;
        luckPermsChecked = true;
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            luckPermsApi = provider.getMethod("get").invoke(null);
        } catch (Throwable ignored) {
            luckPermsApi = null;
        }
        return luckPermsApi;
    }

    private List<PermissionHolderSnapshot> readGroups(Object api) {
        List<PermissionHolderSnapshot> snapshots = new ArrayList<>();
        try {
            Object groupManager = call(api, "getGroupManager");
            Object loadFuture = call(groupManager, "loadAllGroups");
            if (loadFuture instanceof CompletableFuture<?> future) future.join();
            Object groups = call(groupManager, "getLoadedGroups");
            if (groups instanceof Collection<?> collection) {
                for (Object group : collection) {
                    String name = stringValue(call(group, "getName"));
                    if (name == null || name.isBlank()) continue;
                    snapshots.add(new PermissionHolderSnapshot("GROUP", name.toLowerCase(Locale.ROOT), null, nodes(group)));
                }
            }
        } catch (Throwable ignored) {
        }
        return snapshots;
    }

    private List<PermissionHolderSnapshot> readUsers(Object api) {
        List<PermissionHolderSnapshot> snapshots = new ArrayList<>();
        try {
            Object userManager = call(api, "getUserManager");
            Object uuidsValue = call(userManager, "loadAllUsers");
            Set<UUID> uuids = new LinkedHashSet<>();
            if (uuidsValue instanceof CompletableFuture<?> future) {
                Object joined = future.join();
                if (joined instanceof Collection<?> collection) {
                    for (Object value : collection) if (value instanceof UUID uuid) uuids.add(uuid);
                }
            } else if (uuidsValue instanceof Collection<?> collection) {
                for (Object value : collection) if (value instanceof UUID uuid) uuids.add(uuid);
            }
            for (UUID uuid : uuids) {
                Object future = call(userManager, "loadUser", UUID.class, uuid);
                Object user = future instanceof CompletableFuture<?> completableFuture ? completableFuture.join() : future;
                if (user == null) continue;
                String name = stringValue(call(user, "getUsername"));
                if (name == null || name.isBlank()) name = uuid.toString();
                Set<String> nodes = nodes(user);
                if (!nodes.isEmpty()) snapshots.add(new PermissionHolderSnapshot("USER", name, uuid, nodes));
            }
        } catch (Throwable ignored) {
        }
        return snapshots;
    }

    private Set<String> nodes(Object holder) {
        Set<String> nodes = new LinkedHashSet<>();
        addNodeKeys(nodes, call(holder, "getNodes"));
        addNodeKeys(nodes, call(holder, "getDistinctNodes"));
        return Set.copyOf(nodes);
    }

    private void addGroupsFromNodes(Set<String> groups, Object nodesValue) {
        if (!(nodesValue instanceof Collection<?> collection)) return;
        for (Object node : collection) {
            String key = nodeKey(node);
            if (key == null) continue;
            if (key.startsWith("group.")) addGroup(groups, key.substring("group.".length()));
        }
    }

    private void addNodeKeys(Set<String> nodes, Object nodesValue) {
        if (!(nodesValue instanceof Collection<?> collection)) return;
        for (Object node : collection) {
            String key = nodeKey(node);
            if (key != null && !key.isBlank()) nodes.add(key.toLowerCase(Locale.ROOT));
        }
    }

    private String nodeKey(Object node) {
        Object key = call(node, "getKey");
        if (key == null) key = call(node, "getPermission");
        return stringValue(key);
    }

    private void addGroup(Set<String> groups, Object value) {
        String group = stringValue(value);
        if (group != null && !group.isBlank()) groups.add(group.toLowerCase(Locale.ROOT));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Object call(Object target, String methodName) {
        return call(target, methodName, null, null);
    }

    private Object call(Object target, String methodName, Class<?> parameterType, Object argument) {
        if (target == null) return null;
        try {
            Method method = parameterType == null ? target.getClass().getMethod(methodName) : target.getClass().getMethod(methodName, parameterType);
            return parameterType == null ? method.invoke(target) : method.invoke(target, argument);
        } catch (Throwable ignored) {
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = parameterType == null ? type.getDeclaredMethod(methodName) : type.getDeclaredMethod(methodName, parameterType);
                method.setAccessible(true);
                return parameterType == null ? method.invoke(target) : method.invoke(target, argument);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private record CacheKey(UUID uuid, String permission) {
    }

    private record CachedPermission(boolean allowed, long createdAt) {
        private boolean isValid() {
            return System.currentTimeMillis() - createdAt <= CACHE_TTL_MILLIS;
        }
    }

    private record CachedGroups(Set<String> groups, long createdAt) {
        private boolean isValid() {
            return System.currentTimeMillis() - createdAt <= CACHE_TTL_MILLIS;
        }
    }
}
