/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * One outstanding, server-observed SWAP or QUICK_MOVE. Client prediction is deliberately not an
 * input. The native adapter supplies immutable item/component keys and only inbound packet data.
 * A source decrement alone never proves storage: the matching destination increment must arrive.
 */
public final class ProtectionInventoryTransaction<K> {
    public enum Result { PENDING, CONFIRMED, FAILED, CANCELLED, TIMED_OUT }
    public record Stack<K>(K key, int count) {
        public Stack {
            if (count < 0 || (count > 0 && key == null) || (count == 0 && key != null)) {
                throw new IllegalArgumentException("Empty stacks have null keys; nonempty stacks need a key");
            }
        }
        public static <K> Stack<K> empty() { return new Stack<>(null, 0); }
    }

    private final Object session;
    private final Object menu;
    private final int containerId;
    private final int sentState;
    private final int source;
    private final Set<Integer> destinations;
    private final Map<Integer, Stack<K>> before;
    private final Map<Integer, Stack<K>> received = new HashMap<>();
    private final Map<Integer, Integer> revisions = new HashMap<>();
    private final BiPredicate<K, K> sameKey;
    private final long deadline;
    private final boolean swap;
    private int confirmedCount;
    private Result result = Result.PENDING;

    public ProtectionInventoryTransaction(Object session, Object menu, int containerId, int stateId,
            int source, Set<Integer> destinations, Map<Integer, Stack<K>> before,
            BiPredicate<K, K> sameKey, boolean swap, long nowTick, int timeoutTicks) {
        if (session == null || menu == null || containerId < 0 || stateId < 0 || stateId > 32767
                || source < 0 || timeoutTicks < 1 || destinations.isEmpty()
                || destinations.contains(source) || before.get(source) == null
                || before.get(source).count() == 0 || !before.keySet().containsAll(destinations)
                || (swap && destinations.size() != 1)) {
            throw new IllegalArgumentException("Invalid protected inventory transaction");
        }
        if (swap && before.get(destinations.iterator().next()).count() != 0) {
            throw new IllegalArgumentException("Protected swaps use an EMPTY hotbar slot, never displace other items");
        }
        this.session = session;
        this.menu = menu;
        this.containerId = containerId;
        this.sentState = stateId;
        this.source = source;
        this.destinations = Set.copyOf(destinations);
        this.before = Map.copyOf(before);
        this.sameKey = java.util.Objects.requireNonNull(sameKey);
        this.swap = swap;
        this.deadline = Math.addExact(nowTick, timeoutTicks);
    }

    /** Only call for actual server slot/content packets, on the client game thread. */
    public void observe(Object session, Object menu, int containerId, int stateId, int slot,
            Stack<K> stack, long nowTick) {
        if (result != Result.PENDING || !owns(session, menu, containerId) || !before.containsKey(slot)) return;
        if (nowTick >= deadline) { result = Result.TIMED_OUT; return; }
        if (!newerState(stateId, sentState)) return;
        Integer previous = revisions.get(slot);
        if (previous != null && stateId != previous && !newerState(stateId, previous)) return;
        if (previous != null && stateId == previous) return; // duplicate packet cannot change evidence
        revisions.put(slot, stateId);
        received.put(slot, stack);
        if (slot != source && !destinations.contains(slot) && !sameStack(before.get(slot), stack)) {
            result = Result.FAILED; // somebody else touched the menu; do not advance the sequence
        }
    }

    public void observeCursor(boolean empty) {
        if (!empty && result == Result.PENDING) result = Result.FAILED;
    }

    public Result poll(Object session, Object menu, int containerId, long nowTick) {
        if (!owns(session, menu, containerId)) return result = Result.CANCELLED;
        if (result != Result.PENDING) return result;
        if (nowTick >= deadline) return result = Result.TIMED_OUT;
        Stack<K> from = received.get(source);
        if (from == null) return result;
        Stack<K> initial = before.get(source);
        if (from.count() > 0 && !sameKey.test(initial.key(), from.key())) return result = Result.FAILED;
        int removed = initial.count() - from.count();
        if (removed < 0) return result = Result.FAILED;
        if (removed == 0) return result;
        int added = 0;
        for (int slot : destinations) {
            Stack<K> old = before.get(slot);
            Stack<K> next = received.getOrDefault(slot, old);
            if (sameStack(old, next)) continue;
            if (next.count() == 0 || !sameKey.test(initial.key(), next.key())) return result = Result.FAILED;
            if (old.count() > 0 && !sameKey.test(initial.key(), old.key())) return result = Result.FAILED;
            int change = next.count() - old.count();
            if (change < 0) return result = Result.FAILED;
            added += change;
        }
        // Slot packets can arrive separately, including destination first. Wait for conservation.
        if (added != removed) return result;
        if (swap && (from.count() != 0 || removed != initial.count())) return result = Result.FAILED;
        confirmedCount = removed;
        return result = Result.CONFIRMED;
    }

    public void cancel() { if (result == Result.PENDING) result = Result.CANCELLED; }
    public Result result() { return result; }
    public int source() { return source; }
    public boolean isSwap() { return swap; }
    public int confirmedCount() { return result == Result.CONFIRMED ? confirmedCount : 0; }

    public boolean owns(Object session, Object menu, int containerId) {
        return this.session == session && this.menu == menu && this.containerId == containerId;
    }

    private boolean sameStack(Stack<K> a, Stack<K> b) {
        return a.count() == b.count() && (a.count() == 0 || sameKey.test(a.key(), b.key()));
    }

    /** Vanilla menu revisions wrap at 32768. Reject stale, ambiguous half-cycle and invalid ids. */
    public static boolean newerState(int incoming, int previous) {
        if (incoming < 0 || incoming > 32767 || previous < 0 || previous > 32767) return false;
        int delta = (incoming - previous) & 32767;
        return delta > 0 && delta < 16384;
    }
}
