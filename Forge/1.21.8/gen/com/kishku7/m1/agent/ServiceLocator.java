package com.kishku7.m1.agent;

/**
 * Seam for Minecraft-coupled services (perception, actuation, world map, ...). Implemented in
 * {@code shared_minecraft}; the core looks services up by type through {@link ActionContext} and
 * never names a Minecraft API itself. Returns {@code null} for an unregistered type.
 */
public interface ServiceLocator {

    /** Resolve a service by its type, or {@code null} if no provider is registered. */
    <T> T lookup(Class<T> type);
}
