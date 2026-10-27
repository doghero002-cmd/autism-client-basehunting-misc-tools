package com.autism.seedcracker.spectre.mechanics;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HomeMeta command order and five-block teleport threshold adapted from Spectre HomeMetaModule
 * (GPL-3.0-only), d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da, cody-raves and contributors.
 * Safety deviations: require a home-set acknowledgement before RTP and a server teleport before
 * movement counts; timeout aborts instead of issuing /home blindly. SET retains delete-delay-set.
 * No game objects, workers, network access or wall-clock reads are owned by this controller.
 */
public final class HomeMetaSequence {
    public enum Step { IDLE, DELETE, SET, WAIT_SET, RTP, WAIT_RTP, HOME, WAIT_HOME, COMPLETE, FAILED }

    private static final long SET_CONFIRM_MS = 10_000;
    private static final Pattern FORMATTING = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOME_NUMBER = Pattern.compile("\\bhome\\s*#?\\s*(\\d+)\\b");
    private static final Pattern SET_CONFIRMED = Pattern.compile(
        "\\bhome(?:\\s*#?\\s*\\d+)?\\s+(?:(?:has been|was|is|successfully)\\s+)*(?:set|created|saved)\\b"
        + "|\\b(?:successfully\\s+)?(?:set|created|saved)\\s+(?:your\\s+)?home\\b");

    private Step step = Step.IDLE;
    private long version;
    private long nextAtMs;
    private long deadlineMs;
    private long runDeadlineMs;
    private long delayMs;
    private long teleportTimeoutMs;
    private boolean meta;
    private boolean teleportSeen;
    private int home;
    private String setCommand;
    private String deleteCommand;
    private String rtpCommand;
    private String failure = "";

    public void startSet(int home, boolean deleteFirst, long delayMs, String set, String delete, long now) {
        start(home, delayMs, set, delete, now);
        meta = false;
        runDeadlineMs = now + 30_000;
        transition(deleteFirst ? Step.DELETE : Step.SET);
        nextAtMs = deleteFirst ? now : now + this.delayMs;
    }

    public void startMeta(int home, long delayMs, long timeoutMs, String set, String delete,
                          String rtp, long now) {
        start(home, delayMs, set, delete, now);
        meta = true;
        rtpCommand = buildCommand(rtp, home, "rtp");
        teleportTimeoutMs = Math.clamp(timeoutMs, 5_000, 60_000);
        runDeadlineMs = now + 180_000;
        transition(Step.DELETE);
    }

    private void start(int home, long delayMs, String set, String delete, long now) {
        cancel();
        this.home = Math.clamp(home, 1, 10);
        this.delayMs = Math.clamp(delayMs, 0, 5_000);
        this.setCommand = buildCommand(set, this.home, "sethome %slot%");
        this.deleteCommand = buildCommand(delete, this.home, "delhome %slot%");
        nextAtMs = now;
    }

    /** The caller sends at most one returned command, after acquiring ActionPacer permission. */
    public String nextCommand(long now, double distanceFromRtpSquared, double distanceFromHomeSquared) {
        if (!running()) return null;
        if (now >= runDeadlineMs) {
            fail("action deadline expired");
            return null;
        }
        if (step == Step.WAIT_SET) {
            if (now >= deadlineMs) fail("home-set confirmation timed out; RTP was not sent");
            return null;
        }
        if (step == Step.WAIT_RTP) {
            if (teleportSeen && Double.isFinite(distanceFromRtpSquared) && distanceFromRtpSquared > 25.0) {
                transition(Step.HOME);
                nextAtMs = now + delayMs;
            } else if (now >= deadlineMs) {
                fail("RTP teleport was not confirmed");
            }
            return null;
        }
        if (step == Step.WAIT_HOME) {
            if (teleportSeen && Double.isFinite(distanceFromHomeSquared) && distanceFromHomeSquared <= 25.0) {
                transition(Step.COMPLETE);
            } else if (now >= deadlineMs) {
                fail("return-home teleport was not confirmed");
            }
            return null;
        }
        if (now < nextAtMs) return null;
        return switch (step) {
            case DELETE -> deleteCommand;
            case SET -> setCommand;
            case RTP -> rtpCommand;
            case HOME -> "home " + home;
            default -> null;
        };
    }

    /** Publish the expected response state immediately before the synchronous command send. */
    public void commandSent(long now) {
        switch (step) {
            case DELETE -> {
                transition(Step.SET);
                nextAtMs = now + delayMs;
            }
            case SET -> {
                transition(meta ? Step.WAIT_SET : Step.COMPLETE);
                deadlineMs = now + SET_CONFIRM_MS;
            }
            case RTP -> {
                transition(Step.WAIT_RTP);
                deadlineMs = now + teleportTimeoutMs;
            }
            case HOME -> {
                transition(Step.WAIT_HOME);
                deadlineMs = now + teleportTimeoutMs;
            }
            default -> throw new IllegalStateException("No command due in " + step);
        }
    }

    /** System-chat only, delivered on the client thread by the adapter's bounded queue. */
    public void onChatMessage(String message, long now) {
        if (!running() || message == null || message.length() > 4096) return;
        String line = FORMATTING.matcher(message).replaceAll("").toLowerCase(Locale.ROOT).trim();
        boolean homeMissing = line.contains("home") && (line.contains("not found")
            || line.contains("does not exist") || line.contains("don't have") || line.contains("do not have"));
        // Deleting a previously unused slot is valid; the next set still has to be acknowledged.
        if (homeMissing && step == Step.SET) return;
        if (homeMissing || line.contains("teleport cancelled") || line.contains("teleport canceled")
            || line.contains("teleportation cancelled") || line.contains("teleportation canceled")
            || line.contains("you must wait") || line.contains("cooldown")
            || line.contains("cannot set") || line.contains("can't set") || line.contains("unable to set")
            || line.contains("cannot teleport") || line.contains("can't teleport")
            || line.contains("not enough") || line.contains("no permission") || line.startsWith("usage:")) {
            fail("server rejected a home/RTP command");
            return;
        }
        if (step != Step.WAIT_SET || now >= deadlineMs || line.contains("will ")
            || line.contains("not set") || line.contains("to set ")) return;
        var number = HOME_NUMBER.matcher(line);
        if (number.find() && !number.group(1).equals(Integer.toString(home))) return;
        if (SET_CONFIRMED.matcher(line).find()) {
            transition(Step.RTP);
            nextAtMs = now + delayMs;
        }
    }

    public void onServerTeleport() {
        if (step == Step.WAIT_RTP || step == Step.WAIT_HOME) teleportSeen = true;
    }

    public void fail(String reason) {
        failure = reason;
        transition(Step.FAILED);
        clearCommands();
    }

    public void cancel() {
        transition(Step.IDLE);
        failure = "";
        nextAtMs = deadlineMs = runDeadlineMs = 0;
        clearCommands();
    }

    private void clearCommands() {
        setCommand = deleteCommand = rtpCommand = null;
        teleportSeen = false;
    }

    private void transition(Step next) {
        step = next;
        version++;
        teleportSeen = false;
    }

    public Step step() { return step; }
    public long version() { return version; }
    public String failure() { return failure; }
    public boolean running() { return step != Step.IDLE && step != Step.COMPLETE && step != Step.FAILED; }

    /** Keep legacy templates and slash stripping, but reject multiline or oversized commands. */
    public static String buildCommand(String template, int slot, String fallback) {
        String command = template == null || template.isBlank() ? fallback : template.trim();
        command = command.replace("%slot%", Integer.toString(Math.clamp(slot, 1, 10)));
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank() || command.length() > 256 || command.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Home command must be one nonempty line of at most 256 characters");
        }
        return command;
    }
}
