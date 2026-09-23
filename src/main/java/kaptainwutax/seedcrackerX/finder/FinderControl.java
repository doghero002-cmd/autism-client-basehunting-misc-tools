package kaptainwutax.seedcrackerX.finder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class FinderControl {

    private final Map<Finder.Type, ConcurrentLinkedQueue<Finder>> activeFinders = new ConcurrentHashMap<>();

    public void deleteFinders() {
        this.activeFinders.clear();
    }

    public List<Finder> getActiveFinders() {
		// Snapshot per queue first: removeIf racing worker-thread adds can throw CME.
		List<Finder> out = new ArrayList<>();
		for (Queue<Finder> finders : this.activeFinders.values()) {
			List<Finder> snapshot = new ArrayList<>(finders);
			snapshot.removeIf(Finder::isUseless);
			out.addAll(snapshot);
		}
		return out;
	}

	public void addFinder(Finder.Type type, Finder finder) {
		if (finder.isUseless()) return;

		this.activeFinders.computeIfAbsent(type, t -> new ConcurrentLinkedQueue<>()).add(finder);
	}
}
