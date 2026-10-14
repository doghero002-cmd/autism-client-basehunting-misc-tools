package com.autism.seedcracker.chatbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

public final class ChatBotConversation {
    private static final long EXPIRE_MS = 5 * 60_000L;
    private static final int MAX_PLAYERS = 32;
    private final LinkedHashMap<UUID, Session> sessions = new LinkedHashMap<>(32, 0.75f, true);

    public record Turn(String role, String content) {}
    public record Request(UUID sender, String input, List<Turn> turns, int retainTurns) {}

    private static final class Session {
        final ArrayDeque<Turn> history = new ArrayDeque<>();
        long touched;
        Request pending;
    }

    public Request begin(UUID sender, String input, int retainTurns, long now) {
        if (sender == null || input == null || input.isBlank() || input.length() > 512) return null;
        sessions.entrySet().removeIf(entry -> now < entry.getValue().touched || now - entry.getValue().touched > EXPIRE_MS);
        if (!sessions.containsKey(sender) && sessions.size() >= MAX_PLAYERS) sessions.remove(sessions.keySet().iterator().next());
        Session session = sessions.computeIfAbsent(sender, ignored -> new Session());
        int limit = Math.max(1, Math.min(20, retainTurns));
        while (session.history.size() > limit * 2) {
            session.history.removeFirst();
            session.history.removeFirst();
        }
        var turns = new ArrayList<>(session.history);
        turns.add(new Turn("user", input.trim()));
        Request request = new Request(sender, input.trim(), List.copyOf(turns), limit);
        session.pending = request;
        session.touched = now;
        return request;
    }

    public boolean current(Request request) {
        if (request == null) return false;
        Session session = sessions.get(request.sender());
        return session != null && session.pending == request;
    }

    public boolean complete(Request request, String reply, long now) {
        if (!current(request) || reply == null || reply.isBlank() || reply.length() > 220) return false;
        Session session = sessions.get(request.sender());
        if (now < session.touched || now - session.touched > EXPIRE_MS) {
            sessions.remove(request.sender());
            return false;
        }
        session.history.addLast(new Turn("user", request.input()));
        session.history.addLast(new Turn("assistant", reply));
        while (session.history.size() > request.retainTurns() * 2) {
            session.history.removeFirst();
            session.history.removeFirst();
        }
        session.pending = null;
        session.touched = now;
        return true;
    }

    public void clear() { sessions.clear(); }

    public static boolean addressed(String text, String self, String trigger) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return self != null && !self.isBlank() && ChatBotPolicy.containsWord(lower, self.toLowerCase(java.util.Locale.ROOT))
            || trigger != null && !trigger.isBlank() && ChatBotPolicy.containsWord(lower, trigger.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
