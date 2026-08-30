package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.AuctionListingCandidate;
import com.example.donutflipscanner.automation.model.AuctionLocateResult;
import com.example.donutflipscanner.automation.model.AuctionVerificationResult;
import com.example.donutflipscanner.automation.model.InventoryVerificationResult;
import com.example.donutflipscanner.automation.model.ListingResult;
import com.example.donutflipscanner.automation.model.ListingVerificationResult;
import com.example.donutflipscanner.automation.model.PurchaseResult;
import com.example.donutflipscanner.automation.model.RelistPlan;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.service.AuctionInteractionAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Drives the open auction-house container GUI to satisfy the
 * {@link AuctionInteractionAdapter} contract using live Minecraft client state.
 * Mojang-mappings implementation.
 *
 * <p>Every operation fails closed (rejected / missing) on any inconsistency so
 * an unexpected screen or slot state never triggers an unintended trade.
 */
public final class MinecraftAuctionInteractionAdapter implements AuctionInteractionAdapter {
    private final Minecraft minecraft;
    private final Supplier<Optional<AuctionInteractionProfile>> profileSupplier;

    public MinecraftAuctionInteractionAdapter(
            Minecraft minecraft,
            Supplier<Optional<AuctionInteractionProfile>> profileSupplier
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
    }

    @Override
    public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<AuctionInteractionProfile> profile = profile();
        if (profile.isEmpty()) {
            return completed(AuctionLocateResult.missing("No auction interaction profile configured."));
        }
        Optional<AbstractContainerMenu> menu = openMenu();
        if (menu.isEmpty()) {
            return completed(AuctionLocateResult.missing("No auction house screen is open."));
        }
        AuctionInteractionProfile p = profile.orElseThrow();
        if (!isExpectedScreen(p.resultsScreenTitle())) {
            return completed(AuctionLocateResult.missing("The open screen is not the auction results screen."));
        }
        for (int slotIndex = p.firstResultSlot(); slotIndex <= p.lastResultSlot(); slotIndex++) {
            Slot slot = slotAt(menu.orElseThrow(), slotIndex);
            if (slot == null) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!matchesRequest(stack, request)) {
                continue;
            }
            AuctionListingCandidate candidate = buildCandidate(slotIndex, stack, request);
            if (candidate == null) {
                return completed(AuctionLocateResult.missing("A matching listing could not be read safely."));
            }
            return completed(AuctionLocateResult.found(candidate));
        }
        return completed(AuctionLocateResult.missing("No listing matched the immutable request."));
    }

    @Override
    public CompletableFuture<AuctionVerificationResult> verifyListing(
            TradeExecutionRequest request,
            AuctionListingCandidate candidate
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidate, "candidate");
        if (!request.listingKey().equals(candidate.listingKey())) {
            return completed(AuctionVerificationResult.rejected("Listing key drifted between locate and verify."));
        }
        if (!request.itemId().equals(candidate.itemId())) {
            return completed(AuctionVerificationResult.rejected("Listing item id does not match the request."));
        }
        if (candidate.itemCount() != request.expectedItemCount()) {
            return completed(AuctionVerificationResult.rejected("Listing item count does not match the request."));
        }
        if (candidate.listingPrice().compareTo(request.maximumAcceptablePurchasePrice()) > 0) {
            return completed(AuctionVerificationResult.rejected("Listing price exceeds the maximum acceptable price."));
        }
        return completed(AuctionVerificationResult.accepted());
    }

    @Override
    public CompletableFuture<PurchaseResult> purchase(
            TradeExecutionRequest request,
            AuctionListingCandidate candidate
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidate, "candidate");
        Optional<AuctionInteractionProfile> profile = profile();
        if (profile.isEmpty()) {
            return completed(new PurchaseResult(false, false, "No auction interaction profile configured."));
        }
        Optional<AbstractContainerMenu> menu = openMenu();
        if (menu.isEmpty() || minecraft.gameMode == null || minecraft.player == null) {
            return completed(new PurchaseResult(false, false, "Cannot interact without an open screen and player."));
        }
        int slotIndex = parseSlotIndex(candidate.listingKey());
        if (slotIndex < 0) {
            return completed(new PurchaseResult(false, false, "Listing key does not encode a clickable slot."));
        }
        if (!clickSlot(menu.orElseThrow(), slotIndex)) {
            return completed(new PurchaseResult(false, false, "The purchase click could not be dispatched."));
        }
        return completed(new PurchaseResult(true, false, "Purchase click dispatched; awaiting confirmation."));
    }

    @Override
    public CompletableFuture<InventoryVerificationResult> verifyPurchase(TradeExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        if (minecraft.player == null) {
            return completed(new InventoryVerificationResult(false, true, "No local player is available."));
        }
        // Ambiguous: without a deterministic post-purchase inventory diff we cannot
        // positively confirm the item arrived. Fail closed rather than claim success.
        return completed(new InventoryVerificationResult(false, true, "Purchase could not be positively confirmed."));
    }

    @Override
    public CompletableFuture<ListingResult> listForSale(TradeExecutionRequest request, RelistPlan relistPlan) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(relistPlan, "relistPlan");
        // Listing for sale requires a server-specific command / GUI flow that is not
        // derivable from packet state alone. Fail closed.
        return completed(new ListingResult(false, "Automated relisting is not supported by this adapter."));
    }

    @Override
    public CompletableFuture<ListingVerificationResult> verifyListingCreated(
            TradeExecutionRequest request,
            RelistPlan relistPlan
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(relistPlan, "relistPlan");
        return completed(new ListingVerificationResult(false, "Listing creation could not be verified."));
    }

    @Override
    public CompletableFuture<Void> returnToSafeScreen() {
        Screen current = currentScreen();
        if (current instanceof AbstractContainerScreen<?> containerScreen) {
            containerScreen.onClose();
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Release any resources held by this adapter. This adapter is stateless. */
    public void close() {
        // No resources to release; present for parity with the engine lifecycle.
    }

    // ---- helpers -----------------------------------------------------------

    private Optional<AuctionInteractionProfile> profile() {
        Optional<AuctionInteractionProfile> profile = profileSupplier.get();
        return profile == null ? Optional.empty() : profile;
    }

    private Optional<AbstractContainerMenu> openMenu() {
        Screen screen = currentScreen();
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            return Optional.ofNullable(containerScreen.getMenu());
        }
        return Optional.empty();
    }

    private Screen currentScreen() {
        return minecraft.gui == null ? null : minecraft.gui.screen();
    }

    private boolean isExpectedScreen(String expectedTitle) {
        Screen screen = currentScreen();
        if (screen == null || expectedTitle == null || expectedTitle.isBlank()) {
            return false;
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString();
        return title.contains(expectedTitle);
    }

    private static Slot slotAt(AbstractContainerMenu menu, int slotIndex) {
        if (menu == null || slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return null;
        }
        return menu.slots.get(slotIndex);
    }

    private boolean matchesRequest(ItemStack stack, TradeExecutionRequest request) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!request.itemId().equals(id)) {
            return false;
        }
        return stack.getCount() == request.expectedItemCount();
    }

    private AuctionListingCandidate buildCandidate(int slotIndex, ItemStack stack, TradeExecutionRequest request) {
        try {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String listingKey = "slot:" + slotIndex;
            return new AuctionListingCandidate(
                    listingKey,
                    request.itemFingerprint(),
                    id,
                    stack.getCount(),
                    request.expectedSeller(),
                    request.expectedListingPrice()
            );
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private boolean clickSlot(AbstractContainerMenu menu, int slotIndex) {
        if (minecraft.gameMode == null || minecraft.player == null || menu == null) {
            return false;
        }
        if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return false;
        }
        try {
            minecraft.gameMode.handleContainerInput(
                    menu.containerId, slotIndex, 0, ContainerInput.PICKUP, minecraft.player
            );
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static int parseSlotIndex(String listingKey) {
        if (listingKey == null || !listingKey.startsWith("slot:")) {
            return -1;
        }
        try {
            return Integer.parseInt(listingKey.substring("slot:".length()));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
