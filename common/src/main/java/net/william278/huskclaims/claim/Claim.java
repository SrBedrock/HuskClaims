/*
 * This file is part of HuskClaims, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.huskclaims.claim;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.william278.cloplib.operation.Operation;
import net.william278.cloplib.operation.OperationType;
import net.william278.huskclaims.HuskClaims;
import net.william278.huskclaims.user.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link Region} in a {@link ClaimWorld} governed by user {@link TrustLevel}s and a set of
 * base {@link OperationType} flags
 *
 * @see Region
 * @since 1.0
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Claim {

    /**
     * The claim region
     */
    @Getter
    @Expose
    private Region region;

    /**
     * The owner of the claim
     */
    @Expose
    @Nullable
    private UUID owner;

    /**
     * Map of TrustLevels to a list of UUID players with that TrustLevel
     */
    @Expose
    @SerializedName("trusted_users")
    private Map<UUID, String> trustedUsers;

    /**
     * Map of TrustLevels to a list of UUID groups with that TrustLevel
     */
    @Expose
    @SerializedName("trusted_groups")
    private Map<String, String> trustedGroups;

    /**
     * List of child claims
     */
    @Getter
    @Expose
    private ConcurrentLinkedQueue<Claim> children;

    /**
     * List of OperationTypes allowed on this claim to everyone
     */
    @Expose
    @SerializedName("default_flags")
    private List<OperationType> defaultFlags;

    /**
     * If this is a child claim, whether to inherit member trust levels from the parent.
     * <p>
     * If set to false, this child claim will be restricted.
     */
    @Getter
    @Setter
    @Expose
    @SerializedName("inherit_parent")
    private boolean inheritParent;

    private Claim(@Nullable UUID owner, @NotNull Region region, @NotNull ConcurrentMap<UUID, String> trustedUsers,
                  @NotNull ConcurrentMap<String, String> trustedGroups, @NotNull ConcurrentLinkedQueue<Claim> children,
                  boolean inheritParent, @NotNull List<OperationType> defaultFlags) {
        this.owner = owner;
        this.region = region;
        this.trustedUsers = trustedUsers;
        this.trustedGroups = trustedGroups;
        this.children = children;
        this.defaultFlags = defaultFlags;
        this.inheritParent = inheritParent;
    }

    private Claim(@Nullable UUID owner, @NotNull Region region, @NotNull HuskClaims plugin) {
        this(owner, region, Maps.newConcurrentMap(), Maps.newConcurrentMap(), Queues.newConcurrentLinkedQueue(), true,
                owner != null ? plugin.getSettings().getClaimDefaults().getDefaultFlags()
                        : plugin.getSettings().getClaimDefaults().getAdminFlags());
    }

    @NotNull
    public static Claim create(@NotNull User owner, @NotNull Region region, @NotNull HuskClaims plugin) {
        return new Claim(owner.getUuid(), region, plugin);
    }

    @NotNull
    public static Claim createAdminClaim(@NotNull Region region, @NotNull HuskClaims plugin) {
        return new Claim(null, region, plugin);
    }

    /**
     * Get the owner of the claim
     *
     * @return the owner of the claim. A claim is an "admin claim" (owned by the server) if there is no owner.
     * @since 1.0
     */
    public Optional<UUID> getOwner() {
        return Optional.ofNullable(owner);
    }

    public boolean isPrivilegeAllowed(@NotNull TrustLevel.Privilege privilege, @NotNull User user,
                                      @NotNull ClaimWorld world, @NotNull HuskClaims plugin) {
        return getEffectiveTrustLevel(user, world, plugin)
                .map(level -> level.getPrivileges().contains(privilege))
                .orElse(false);
    }

    /**
     * Get the user's explicit {@link TrustLevel} in this claim. This does not take into account parent claims;
     * see {@link #getEffectiveTrustLevel(User, ClaimWorld, HuskClaims)}.
     *
     * @param user   the user to get the trust level for
     * @param plugin the plugin instance
     * @return the user's trust level, if they have one defined
     */
    public Optional<TrustLevel> getTrustLevel(@NotNull UUID user, @NotNull HuskClaims plugin) {
        if (trustedUsers.containsKey(user)) {
            return plugin.getTrustLevel(trustedUsers.get(user));
        }

        if (owner == null) {
            return Optional.empty();
        }

        return trustedGroups.entrySet().stream()
                .filter(entry -> plugin.getUserGroup(owner, entry.getKey())
                        .map(group -> group.isMember(user))
                        .orElse(false)
                ).findFirst()
                .flatMap(entry -> plugin.getTrustLevel(entry.getValue()));
    }

    /**
     * Get the user's effective {@link TrustLevel}, taking parent claims into account
     *
     * @param user   the user to get the effective trust level for
     * @param world  the world the claim is in
     * @param plugin the plugin instance
     * @return the user's effective trust level, if they have one defined
     * @since 1.0
     */
    @NotNull
    public Optional<TrustLevel> getEffectiveTrustLevel(@NotNull User user, @NotNull ClaimWorld world,
                                                       @NotNull HuskClaims plugin) {
        return getTrustLevel(user.getUuid(), plugin)
                .or(() -> inheritParent
                        ? getParent(world).flatMap(parent -> parent.getEffectiveTrustLevel(user, world, plugin))
                        : Optional.empty());
    }

    public boolean isOperationAllowed(@NotNull Operation operation, @NotNull ClaimWorld world,
                                      @NotNull HuskClaims plugin) {
        // If the operation is explicitly allowed, return it
        return defaultFlags.contains(operation.getType())

                // Or, if the user is the owner, return true
                || (owner != null && operation.getUser()
                .map(user -> owner.equals(user.getUuid()))
                .orElse(false))

                // Or, if there's a user involved in this operation, check their rights
                || (operation.getUser()
                .flatMap(user -> getTrustLevel(user.getUuid(), plugin)
                        .map(level -> level.getFlags().contains(operation.getType())))
                .orElse(false))

                // Or, if the user doesn't have a trust level here, try getting it from the parent
                || (inheritParent && getParent(world)
                .map(parent -> parent.isOperationAllowed(operation, world, plugin))
                .orElse(false));
    }

    public Optional<Claim> getParent(@NotNull ClaimWorld world) {
        return world.getClaims().stream()
                .filter(claim -> claim.getChildren().contains(this))
                .findFirst();
    }

    public boolean isChildClaim(@NotNull ClaimWorld world) {
        return getParent(world).isPresent();
    }

    public boolean isAdminClaim(@NotNull ClaimWorld world) {
        if (isChildClaim(world)) {
            return getParent(world).map(parent -> parent.isAdminClaim(world)).orElse(false);
        } else {
            return getOwner().isEmpty();
        }
    }

    @NotNull
    public Claim createAndAddChild(@NotNull Region subRegion, @NotNull ClaimWorld world, @NotNull HuskClaims plugin)
            throws IllegalArgumentException {
        if (isChildClaim(world)) {
            throw new IllegalArgumentException("A child claim cannot be within another child claim");
        }
        if (!region.contains(subRegion.getNearCorner()) || !region.contains(subRegion.getFarCorner())) {
            throw new IllegalArgumentException("Child claim must be contained within parent claim");
        }
        final Claim child = new Claim(owner, region, plugin);
        children.add(child);
        return child;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Claim claim) {
            return claim.region.equals(region)
                    && claim.children.equals(children);
        }
        return false;
    }

}
