package com.example.donutflipscanner.packet;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiPaginationMetadata;
import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Accumulates auction-house slot state pushed in from intercepted server
 * packets and exposes the currently-open page as an {@link ApiAuctionPage}.
 * Mojang-mappings implementation.
 *
 * <p>Data that cannot be positively derived from the packet payload (price,
 * seller, time-left) is left empty so consumers fail closed rather than act on
 * fabricated values.
 */
public final class AuctionHouseDataCapture {
    private final Supplier<Optional<AuctionInteractionProfile>> profileSupplier;
    private final ConcurrentHashMap<Integer, ItemStack> slots = new ConcurrentHashMap<>();

    private volatile int openSyncId = -1;
    private volatile String openTitle = "";

    public AuctionHouseDataCapture(Supplier<Optional<AuctionInteractionProfile>> profileSupplier) {
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
    }

    /** Called when the server opens an auction screen for {@code syncId}. */
    public void onScreenOpened(int syncId, String title) {
        slots.clear();
        openSyncId = syncId;
        openTitle = title == null ? "" : title;
    }

    /** Called when the server sends the full contents of the open screen. */
    public void onInventorySynced(int syncId, List<ItemStack> contents) {
        if (syncId != openSyncId || contents == null) {
            return;
        }
        slots.clear();
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack != null && !stack.isEmpty()) {
                slots.put(i, stack.copy());
            }
        }
    }

    /** Called when the server updates a single slot of the open screen. */
    public void onSlotUpdated(int syncId, int slot, ItemStack stack) {
        if (syncId != openSyncId) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            slots.remove(slot);
        } else {
            slots.put(slot, stack.copy());
        }
    }

    /** Called when the server closes the open screen. */
    public void onScreenClosed(int syncId) {
        if (syncId == openSyncId) {
            openSyncId = -1;
            openTitle = "";
            slots.clear();
        }
    }

    /**
     * Build an {@link ApiAuctionPage} from the captured slots of the current
     * screen, or {@link Optional#empty()} when no auction screen is open or no
     * interaction profile is configured.
     */
    public Optional<ApiAuctionPage> captureCurrentPage() {
        if (openSyncId < 0) {
            return Optional.empty();
        }
        Optional<AuctionInteractionProfile> profile = profileSupplier.get();
        if (profile == null || profile.isEmpty()) {
            return Optional.empty();
        }
        AuctionInteractionProfile p = profile.orElseThrow();
        List<ApiAuctionListing> listings = new ArrayList<>();
        for (int slotIndex = p.firstResultSlot(); slotIndex <= p.lastResultSlot(); slotIndex++) {
            ItemStack stack = slots.get(slotIndex);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ApiAuctionListing listing = toListing(stack);
            if (listing != null) {
                listings.add(listing);
            }
        }
        if (listings.isEmpty()) {
            return Optional.empty();
        }
        ApiPaginationMetadata pagination = ApiPaginationMetadata.listings(1, listings.size());
        return Optional.of(new ApiAuctionPage(200, listings, pagination));
    }

    private static ApiAuctionListing toListing(ItemStack stack) {
        try {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            ApiAuctionItem item = new ApiAuctionItem(
                    Optional.of(id),
                    OptionalInt.of(stack.getCount()),
                    Optional.empty(),
                    List.of(),
                    Optional.empty(),
                    List.of()
            );
            // Price / seller / time-left are not positively derivable from raw slot
            // data, so they are left empty and consumers fail closed.
            return new ApiAuctionListing(
                    Optional.of(item),
                    Optional.empty(),
                    Optional.empty(),
                    java.util.OptionalLong.empty()
            );
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
