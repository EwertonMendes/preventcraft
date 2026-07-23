package tblack.preventcraft.permissions;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.ModConstants;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class PermissionService {
    private static final long CACHE_TTL_MILLIS = 5000L;

    private final Map<CacheKey, CachedPermission> permissionCache = new ConcurrentHashMap<>();
    private final Map<UUID, CachedGroups> groupCache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAuthorizationSnapshot> authorizationCache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerIdentity> onlinePlayers = new ConcurrentHashMap<>();
    private final Set<UUID> pendingAuthorizationLoads = ConcurrentHashMap.newKeySet();
    private final ExecutorService authorizationExecutor = Executors.newSingleThreadExecutor(new AuthorizationThreadFactory());
    private final AtomicLong authorizationVersion = new AtomicLong();
    private volatile Consumer<AuthorizationUpdate> authorizationListener = update -> { };
    private volatile boolean luckPermsChecked;
    private volatile Object luckPermsApi;
    private volatile Object luckPermsSubscription;

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

    /** Returns immediately and never queries a permission provider. */
    public PlayerAuthorizationSnapshot cachedAuthorization(PlayerRef playerRef) {
        if (playerRef == null) return PlayerAuthorizationSnapshot.pending(null, "");
        UUID uuid;
        String username = "";
        try {
            uuid = playerRef.getUuid();
            username = playerRef.getUsername();
        } catch (Throwable ignored) {
            return PlayerAuthorizationSnapshot.pending(null, username);
        }
        PlayerAuthorizationSnapshot snapshot = authorizationCache.get(uuid);
        return snapshot == null ? PlayerAuthorizationSnapshot.pending(uuid, username) : snapshot;
    }

    public void setAuthorizationListener(Consumer<AuthorizationUpdate> listener) {
        authorizationListener = listener == null ? update -> { } : listener;
    }

    public List<AuthorizationUpdate> onlineAuthorizations() {
        List<AuthorizationUpdate> snapshots = new ArrayList<>(onlinePlayers.size());
        for (PlayerIdentity identity : onlinePlayers.values()) {
            PlayerAuthorizationSnapshot snapshot = authorizationCache.get(identity.uuid());
            if (snapshot == null) snapshot = PlayerAuthorizationSnapshot.pending(identity.uuid(), identity.username());
            snapshots.add(new AuthorizationUpdate(identity.playerRef(), snapshot));
        }
        return List.copyOf(snapshots);
    }

    /** Schedules authorization work away from the world thread. */
    public void playerReady(PlayerRef playerRef) {
        if (playerRef == null) return;
        try {
            UUID uuid = playerRef.getUuid();
            String username = playerRef.getUsername();
            onlinePlayers.put(uuid, new PlayerIdentity(uuid, username, playerRef));
            PlayerAuthorizationSnapshot pending = authorizationCache.computeIfAbsent(
                    uuid, ignored -> PlayerAuthorizationSnapshot.pending(uuid, username));
            notifyAuthorizationUpdated(playerRef, pending);
            if (luckPermsSubscription == null) {
                luckPermsChecked = false;
                registerLuckPermsListener(this);
            }
            refreshAuthorization(uuid);
        } catch (Throwable ignored) {
        }
    }

    public void playerDisconnected(UUID uuid) {
        if (uuid == null) return;
        onlinePlayers.remove(uuid);
        authorizationCache.remove(uuid);
        pendingAuthorizationLoads.remove(uuid);
        permissionCache.keySet().removeIf(key -> uuid.equals(key.uuid()));
        groupCache.remove(uuid);
    }

    public void refreshAuthorization(UUID uuid) {
        if (uuid == null || !onlinePlayers.containsKey(uuid)) return;
        authorizationExecutor.execute(() -> rebuildAuthorization(uuid, null));
    }

    public void refreshAllAuthorizations() {
        for (UUID uuid : onlinePlayers.keySet()) refreshAuthorization(uuid);
    }

    /** Hooks LuckPerms recalculation events without making LuckPerms a hard dependency. */
    public void registerLuckPermsListener(Object owner) {
        if (luckPermsSubscription != null) return;
        try {
            Object api = getLuckPermsApi();
            if (api == null) return;
            Object eventBus = call(api, "getEventBus");
            Class<?> eventClass = Class.forName("net.luckperms.api.event.user.UserDataRecalculateEvent");
            Consumer<Object> consumer = event -> {
                Object user = call(event, "getUser");
                Object uuidValue = call(user, "getUniqueId");
                if (uuidValue instanceof UUID uuid && onlinePlayers.containsKey(uuid)) {
                    authorizationExecutor.execute(() -> rebuildAuthorization(uuid, user));
                }
            };
            Method subscribe = eventBus.getClass().getMethod("subscribe", Object.class, Class.class, Consumer.class);
            luckPermsSubscription = subscribe.invoke(eventBus, owner == null ? this : owner, eventClass, consumer);
        } catch (Throwable ignored) {
            luckPermsSubscription = null;
        }
    }

    public void shutdown() {
        Object subscription = luckPermsSubscription;
        luckPermsSubscription = null;
        if (subscription != null) call(subscription, "close");
        authorizationExecutor.shutdownNow();
        authorizationCache.clear();
        onlinePlayers.clear();
        pendingAuthorizationLoads.clear();
    }

    private void rebuildAuthorization(UUID uuid, Object providedUser) {
        PlayerIdentity identity = onlinePlayers.get(uuid);
        if (identity == null) return;
        try {
            boolean nativeWildcard = hasNativePermission(uuid, "*");
            boolean bypassAll = nativeWildcard || hasNativePermission(uuid, ModConstants.BYPASS_PERMISSION);
            boolean bypassCraft = bypassAll || hasNativePermission(uuid, ModConstants.BYPASS_CRAFT_PERMISSION);
            boolean bypassBench = bypassAll || hasNativePermission(uuid, ModConstants.BYPASS_BENCH_PERMISSION);
            Set<String> groups = new LinkedHashSet<>();

            Object api = getLuckPermsApi();
            Object user = providedUser != null ? providedUser : loadLuckPermsUserNonBlocking(uuid);
            if (user != null) {
                Object permissionData = call(call(user, "getCachedData"), "getPermissionData");
                boolean luckWildcard = checkPermissionData(permissionData, "*");
                bypassAll |= luckWildcard || checkPermissionData(permissionData, ModConstants.BYPASS_PERMISSION);
                bypassCraft |= bypassAll || checkPermissionData(permissionData, ModConstants.BYPASS_CRAFT_PERMISSION);
                bypassBench |= bypassAll || checkPermissionData(permissionData, ModConstants.BYPASS_BENCH_PERMISSION);
                addGroup(groups, call(user, "getPrimaryGroup"));
                addGroupsFromNodes(groups, call(user, "getNodes"));
            } else if (api != null) {
                requestLuckPermsUserAsync(uuid, api);
            }

            PlayerAuthorizationSnapshot snapshot = new PlayerAuthorizationSnapshot(
                    uuid,
                    identity.username(),
                    Set.copyOf(groups),
                    bypassAll,
                    bypassCraft,
                    bypassBench,
                    user != null,
                    authorizationVersion.incrementAndGet()
            );
            authorizationCache.put(uuid, snapshot);
            notifyAuthorizationUpdated(identity.playerRef(), snapshot);
        } catch (Throwable ignored) {
            authorizationCache.putIfAbsent(uuid, PlayerAuthorizationSnapshot.pending(uuid, identity.username()));
        }
    }

    private Object loadLuckPermsUserNonBlocking(UUID uuid) {
        try {
            Object api = getLuckPermsApi();
            if (api == null) return null;
            return call(call(api, "getUserManager"), "getUser", UUID.class, uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void requestLuckPermsUserAsync(UUID uuid, Object api) {
        if (!pendingAuthorizationLoads.add(uuid)) return;
        try {
            Object userManager = call(api, "getUserManager");
            Object futureValue = call(userManager, "loadUser", UUID.class, uuid);
            if (futureValue instanceof CompletableFuture<?> future) {
                future.whenComplete((user, throwable) -> {
                    pendingAuthorizationLoads.remove(uuid);
                    if (throwable == null && user != null && onlinePlayers.containsKey(uuid)) {
                        authorizationExecutor.execute(() -> rebuildAuthorization(uuid, user));
                    }
                });
            } else {
                pendingAuthorizationLoads.remove(uuid);
            }
        } catch (Throwable ignored) {
            pendingAuthorizationLoads.remove(uuid);
        }
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

    public PermissionSnapshot snapshot(PlayerRef playerRef, Collection<String> permissions, boolean includeGroups) {
        if (playerRef == null) return new PermissionSnapshot(Map.of(), Set.of());
        UUID uuid;
        try {
            uuid = playerRef.getUuid();
        } catch (Throwable ignored) {
            return new PermissionSnapshot(Map.of(), Set.of());
        }

        Map<String, Boolean> resolved = new HashMap<>();
        List<String> unresolved = new ArrayList<>();
        if (permissions != null) {
            for (String permission : permissions) {
                if (permission == null || permission.isBlank()) continue;
                String normalized = permission.toLowerCase(Locale.ROOT);
                CachedPermission cached = permissionCache.get(new CacheKey(uuid, normalized));
                if (cached != null && cached.isValid()) resolved.put(normalized, cached.allowed);
                else unresolved.add(normalized);
            }
        }

        CachedGroups cachedGroups = includeGroups ? groupCache.get(uuid) : null;
        boolean groupsMissing = includeGroups && (cachedGroups == null || !cachedGroups.isValid());
        boolean nativeWildcard = false;
        List<String> luckPermsChecks = new ArrayList<>();
        if (!unresolved.isEmpty()) {
            nativeWildcard = hasNativePermission(uuid, "*");
            for (String permission : unresolved) {
                if (nativeWildcard || hasNativePermission(uuid, permission)) {
                    resolved.put(permission, true);
                    permissionCache.put(new CacheKey(uuid, permission), new CachedPermission(true, System.currentTimeMillis()));
                } else {
                    luckPermsChecks.add(permission);
                }
            }
        }

        Set<String> groups = cachedGroups != null && cachedGroups.isValid() ? cachedGroups.groups : Set.of();
        if (!luckPermsChecks.isEmpty() || groupsMissing) {
            try {
                Object user = loadLuckPermsUser(uuid);
                Object permissionData = call(call(user, "getCachedData"), "getPermissionData");
                boolean luckPermsWildcard = checkPermissionData(permissionData, "*");
                for (String permission : luckPermsChecks) {
                    boolean allowed = luckPermsWildcard || checkPermissionData(permissionData, permission);
                    resolved.put(permission, allowed);
                    permissionCache.put(new CacheKey(uuid, permission), new CachedPermission(allowed, System.currentTimeMillis()));
                }
                if (groupsMissing) {
                    Set<String> loadedGroups = new LinkedHashSet<>();
                    if (user != null) {
                        addGroup(loadedGroups, call(user, "getPrimaryGroup"));
                        addGroupsFromNodes(loadedGroups, call(user, "getNodes"));
                    }
                    groups = Set.copyOf(loadedGroups);
                    groupCache.put(uuid, new CachedGroups(groups, System.currentTimeMillis()));
                }
            } catch (Throwable ignored) {
                for (String permission : luckPermsChecks) {
                    resolved.put(permission, false);
                    permissionCache.put(new CacheKey(uuid, permission), new CachedPermission(false, System.currentTimeMillis()));
                }
                if (groupsMissing) {
                    groups = Set.of();
                    groupCache.put(uuid, new CachedGroups(groups, System.currentTimeMillis()));
                }
            }
        }
        return new PermissionSnapshot(Map.copyOf(resolved), groups);
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
        refreshAllAuthorizations();
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

    private boolean checkPermissionData(Object permissionData, String permission) {
        Object result = call(permissionData, "checkPermission", String.class, permission);
        return Boolean.TRUE.equals(call(result, "asBoolean"));
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

    private void notifyAuthorizationUpdated(PlayerRef playerRef, PlayerAuthorizationSnapshot snapshot) {
        try {
            authorizationListener.accept(new AuthorizationUpdate(playerRef, snapshot));
        } catch (Throwable ignored) {
        }
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

    public record PermissionSnapshot(Map<String, Boolean> permissions, Set<String> groups) {
        public boolean has(String permission) {
            if (permission == null) return false;
            return Boolean.TRUE.equals(permissions.get(permission.toLowerCase(Locale.ROOT)));
        }
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

    public record AuthorizationUpdate(PlayerRef playerRef, PlayerAuthorizationSnapshot snapshot) {
    }

    private record PlayerIdentity(UUID uuid, String username, PlayerRef playerRef) {
    }

    private static final class AuthorizationThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "PreventCraft-Authorization");
            thread.setDaemon(true);
            return thread;
        }
    }
}
