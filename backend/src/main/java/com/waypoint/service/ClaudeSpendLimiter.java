package com.waypoint.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A cumulative spending cap for the AI agent's paid Claude calls. {@link #reserve} runs BEFORE a request is sent and
 * refuses it when the worst-case cost would take the total past the cap; {@link #settle} replaces the reservation with
 * the real cost from the response's usage. A request that fails after it may have been sent keeps its reservation (the
 * cost is unknown, so it is counted at the worst case). The total can be kept in a file so a restart does not reset it.
 * With a ledger file, a MISSING, unreadable or malformed file fails closed (create it with 0 to start counting), and so
 * does a failed save. A cap of zero or less means "no cap" (the default, so existing behaviour is unchanged).
 */
final class ClaudeSpendLimiter {

    /** USD per million tokens. */
    record Prices(double input, double output, double cacheWrite, double cacheRead) {
        static final Prices HAIKU = new Prices(0.10, 0.50, 0.125, 0.01);
        /**
         * Any model not listed is priced at a deliberately high rate, so an unfamiliar or dearer model can only make the cap
         * stricter, never let spending exceed it. A request whose worst case would not fit the cap is refused.
         */
        static final Prices CONSERVATIVE = new Prices(15.0, 75.0, 18.75, 1.5);

        static Prices forModel(String model) {
            return model != null && model.startsWith("claude-haiku-5") ? HAIKU : CONSERVATIVE;
        }

        double cost(ClaudeChatClient.RouteUsage usage) {
            return (usage.inputTokens() * input + usage.outputTokens() * output
                    + usage.cacheWriteTokens() * cacheWrite + usage.cacheReadTokens() * cacheRead) / 1_000_000d;
        }

        /** Every input token at the dearest input rate, the full output allowance. */
        double worstCase(int inputTokens, int maxOutputTokens) {
            return (inputTokens * Math.max(input, cacheWrite) + maxOutputTokens * output) / 1_000_000d;
        }
    }

    private final double capUsd;
    private final Path file;
    private final Prices prices;
    private double spent;
    /** Set when the total cannot be trusted or cannot be saved: every later request is refused. */
    private boolean closed;

    ClaudeSpendLimiter(double capUsd, Path file, Prices prices) {
        this.capUsd = capUsd;
        this.file = file;
        this.prices = prices == null ? Prices.HAIKU : prices;
        this.spent = load();
    }

    /**
     * The limiter for the configured settings. A cap with no ledger file cannot be enforced across restarts, so it
     * refuses everything; no cap means no limit (the default, so existing behaviour is unchanged).
     */
    static ClaudeSpendLimiter fromSettings(double capUsd, String ledgerFile) {
        boolean hasLedger = ledgerFile != null && !ledgerFile.isBlank();
        if (capUsd > 0 && !hasLedger) return closed(capUsd);
        return new ClaudeSpendLimiter(capUsd, hasLedger ? Path.of(ledgerFile.strip()) : null, null);
    }

    /** A limiter that refuses every request (a cap was asked for but its ledger was not configured). */
    static ClaudeSpendLimiter closed(double capUsd) {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(capUsd, null, null);
        limiter.closed = true;
        return limiter;
    }

    boolean enabled() {
        return capUsd > 0;
    }

    Prices prices() {
        return prices;
    }

    synchronized double spent() {
        return spent;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /** Reserves the worst case, or throws before anything is sent. Returns the amount reserved. */
    synchronized double reserve(double worstCaseUsd) {
        if (!enabled()) return 0;
        if (closed || !(worstCaseUsd >= 0) || Double.isInfinite(worstCaseUsd)
                || spent + worstCaseUsd > capUsd) {
            throw new ClaudeChatClient.ClaudeSpendLimitException();
        }
        double before = spent;
        spent += worstCaseUsd;
        if (!save()) {
            spent = before;
            closed = true;
            throw new ClaudeChatClient.ClaudeSpendLimitException();
        }
        return worstCaseUsd;
    }

    synchronized void settle(double reserved, ClaudeChatClient.RouteUsage usage) {
        settle(reserved, usage, prices);
    }

    /** Replaces the reservation with the real cost of {@code usage} at the rates of the model that answered. */
    synchronized void settle(double reserved, ClaudeChatClient.RouteUsage usage, Prices rates) {
        if (!enabled()) return;
        spent = Math.max(0, spent - reserved + rates.cost(usage));
        if (!save()) closed = true;
    }

    /** Gives a reservation back: only for a request the provider certainly did not process (a rejected 4xx). */
    synchronized void release(double reserved) {
        if (!enabled() || reserved <= 0) return;
        spent = Math.max(0, spent - reserved);
        if (!save()) closed = true;
    }

    /** A missing, unreadable or malformed ledger means the total is unknown: treat the whole cap as spent. */
    private double load() {
        if (file == null) return 0;
        try {
            if (!Files.exists(file)) return failClosed();
            double value = Double.parseDouble(Files.readString(file, StandardCharsets.UTF_8).strip());
            if (!Double.isFinite(value) || value < 0) return failClosed();
            return value;
        } catch (IOException | NumberFormatException unreadable) {
            return failClosed();
        }
    }

    private double failClosed() {
        closed = true;
        return capUsd;
    }

    private boolean save() {
        if (file == null) return true;
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, String.format(java.util.Locale.ROOT, "%.8f", spent), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }
}
