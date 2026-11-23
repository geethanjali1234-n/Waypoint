package com.waypoint.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Temporary, opaque person references for the model: {@code [P1]}, {@code [P2]}. The model reasons over authorized
 * records and writes answers with handles; the server swaps real names back in only after it has checked the answer.
 *
 * <ul>
 *   <li><b>Nothing identifying is model-visible.</b> Names in tool results, in the user's message and in replayed
 *       history are replaced by handles before anything leaves the process. A handle means nothing outside its table.</li>
 *   <li><b>A handle lives in one table, bound to one actor and one login session</b> ({@link Registry}). A handle from
 *       another session, another user, or one the server never issued does not resolve; the answer is then rejected.</li>
 *   <li><b>Substitution re-checks scope.</b> A handle for a person who is in the actor's authorized scope RIGHT NOW is
 *       replaced by that person's current name from the current scope list; a person no longer in scope does not resolve.
 *       A user can type "[P3]"; it resolves only to someone in their own scope and session, which they could already see.</li>
 *   <li><b>This is pseudonymization, not anonymization.</b> The server can re-identify, so records behind a handle remain
 *       personal data and stay under the organization's record approval. Handles are not a substitute for that approval;
 *       they stop the NAMES themselves (and anything derived from them, such as e-mail or ID) from leaving.</li>
 * </ul>
 *
 * The name list comes from records the actor may already see (their own profile, their manager, their review scope);
 * replacement is by exact name, never by guessing at wording.
 */
final class WaviPersonHandles {

    /** A person the actor may already see. {@code userId} is null for a name seen only as text (e.g. a manager's name). */
    record Person(String userId, String name) {
        String key() {
            return userId != null && !userId.isBlank() ? userId : "name:" + normalize(name);
        }
    }

    private static final Pattern HANDLE = Pattern.compile("\\[P(\\d{1,4})\\]");

    /** One actor's handles in one session. Server memory only; never sent anywhere. */
    static final class Table {
        private final Map<String, String> keyToHandle = new LinkedHashMap<>();
        private final Map<String, String> handleToKey = new HashMap<>();
        private final Map<String, String> keyToName = new HashMap<>();
        private int next = 1;
        private String lastExchange;
        private long touchedMillis;

        synchronized String handleFor(Person person) {
            String key = person.key();
            String handle = keyToHandle.get(key);
            if (handle == null) {
                handle = "P" + next++;
                keyToHandle.put(key, handle);
                handleToKey.put(handle, key);
            }
            keyToName.put(key, person.name());
            return handle;
        }

        synchronized String keyFor(String handle) {
            return handleToKey.get(handle);
        }

        synchronized String lastKnownName(String key) {
            return keyToName.get(key);
        }

        /** Names this table has already turned into handles, so history that mentions a person who has since left the
         *  actor's scope is still hidden. */
        synchronized Map<String, String> knownNamesByKey() {
            return new HashMap<>(keyToName);
        }

        synchronized void rememberExchange(String exchangeWithHandles) {
            this.lastExchange = exchangeWithHandles;
        }

        synchronized String lastExchange() {
            return lastExchange;
        }

        synchronized void touch(long nowMillis) {
            touchedMillis = nowMillis;
        }

        synchronized long touched() {
            return touchedMillis;
        }
    }

    /** Tables by actor and session; bounded and expiring. */
    static final class Registry {
        private final long ttlMillis;
        private final int maxTables;
        private final Map<String, Table> tables = new LinkedHashMap<>(64, 0.75f, true);

        Registry(java.time.Duration ttl, int maxTables) {
            this.ttlMillis = ttl.toMillis();
            this.maxTables = maxTables;
        }

        Table table(String actorKey, String sessionKey, long nowMillis) {
            String key = actorKey + "|" + sessionKey;
            synchronized (tables) {
                tables.values().removeIf(table -> nowMillis - table.touched() > ttlMillis);
                Table table = tables.computeIfAbsent(key, ignored -> new Table());
                table.touch(nowMillis);
                while (tables.size() > maxTables) {
                    String eldest = tables.keySet().iterator().next();
                    tables.remove(eldest);
                }
                return table;
            }
        }
    }

    private final Table table;
    /** Everyone to hide: the current scope plus names this session has already hidden. Longest names first. */
    private final List<Candidate> candidates;

    private record Candidate(Person person, Pattern pattern) {
    }

    WaviPersonHandles(Table table, List<Person> visible) {
        this.table = table;
        Map<String, Person> byKey = new LinkedHashMap<>();
        for (Person person : visible == null ? List.<Person>of() : visible) {
            if (person == null || person.name() == null || person.name().isBlank()) continue;
            byKey.putIfAbsent(person.key(), person);
        }
        table.knownNamesByKey().forEach((key, name) -> byKey.putIfAbsent(key,
                new Person(key.startsWith("name:") ? null : key, name)));
        List<Candidate> list = new ArrayList<>();
        for (Person person : byKey.values()) {
            list.add(new Candidate(person, namePattern(person.name())));
        }
        list.sort(Comparator.comparingInt((Candidate candidate) -> candidate.person().name().length()).reversed());
        this.candidates = List.copyOf(list);
    }

    private static Pattern namePattern(String name) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name.strip()) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** True when at least one name is known, so hiding can actually hide something. */
    boolean active() {
        return !candidates.isEmpty();
    }

    /** Full names (and only full names) become handles: tool results, history and the previous exchange. */
    String hide(String text) {
        if (text == null || text.isEmpty()) return "";
        // Longest names claim their text first (so "Ravi Teja" is not split by a shorter name); handles are then issued in
        // the order the people appear, which keeps [P1] the first person the reader meets.
        record Hit(int start, int end, Candidate candidate) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Matcher matcher = candidate.pattern().matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                boolean overlaps = hits.stream().anyMatch(hit -> start < hit.end() && end > hit.start());
                if (!overlaps) hits.add(new Hit(start, end, candidate));
            }
        }
        if (hits.isEmpty()) return text;
        hits.sort(Comparator.comparingInt(Hit::start));
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (Hit hit : hits) {
            out.append(text, cursor, hit.start()).append('[').append(table.handleFor(hit.candidate().person())).append(']');
            cursor = hit.end();
        }
        return out.append(text, cursor, text.length()).toString();
    }

    /**
     * The user's own message: full names, then a single name part (first or last name) that belongs to exactly one
     * person and is written with a capital in the middle of the message. A word that is merely a name elsewhere
     * ("will", "rose") is not touched.
     */
    String hideMessage(String message) {
        String result = hide(message);
        Map<String, List<Candidate>> byToken = new HashMap<>();
        for (Candidate candidate : candidates) {
            for (String token : candidate.person().name().strip().split("\\s+")) {
                if (token.length() >= 3) byToken.computeIfAbsent(token.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        // A name part found in the message gets its handle in the order the people appear, not in map order.
        record TokenHit(int position, Pattern pattern, Candidate candidate) {
        }
        List<TokenHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> entry : byToken.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            Pattern capitalised = Pattern.compile("(?<=\\S\\s)(?<![\\p{L}\\p{N}])" + Pattern.quote(capitalise(entry.getKey()))
                    + "(?![\\p{L}\\p{N}])");
            Matcher matcher = capitalised.matcher(result);
            if (matcher.find()) hits.add(new TokenHit(matcher.start(), capitalised, entry.getValue().getFirst()));
        }
        hits.sort(Comparator.comparingInt(TokenHit::position));
        for (TokenHit hit : hits) {
            String handle = "[" + table.handleFor(hit.candidate().person()) + "]";
            result = hit.pattern().matcher(result).replaceAll(Matcher.quoteReplacement(handle));
        }
        return result;
    }

    private static String capitalise(String lower) {
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * The person a handle stands for, but ONLY if the handle was issued in this table and that person is in the actor's
     * CURRENT scope. A guessed, stale, foreign or out-of-scope handle gives null, and nothing about who it might have
     * been. This is how a handle becomes a validated tool argument: the caller then runs the tool for that person's name
     * inside the same scope-checked lookup a typed name would use.
     */
    Person resolve(String handle, List<Person> currentVisible) {
        if (handle == null || !handle.matches("P\\d{1,4}")) return null;
        String key = table.keyFor(handle);
        if (key == null) return null;
        for (Person person : currentVisible == null ? List.<Person>of() : currentVisible) {
            if (person != null && person.name() != null && key.equals(person.key())) return person;
        }
        return null;
    }

    /** True if text still contains a name this object knows (it should not, after {@link #hide}). */
    boolean containsKnownName(String text) {
        if (text == null) return false;
        for (Candidate candidate : candidates) {
            if (candidate.pattern().matcher(text).find()) return true;
        }
        return false;
    }

    /** The handles used in a text, as "P1". */
    static Set<String> handlesIn(String text) {
        Set<String> handles = new LinkedHashSet<>();
        if (text == null) return handles;
        Matcher matcher = HANDLE.matcher(text);
        while (matcher.find()) handles.add("P" + matcher.group(1));
        return handles;
    }

    /**
     * The result of swapping handles for names.
     *
     * @param unresolved handles that were not issued in this session, or whose person is no longer in scope
     */
    record Revealed(String text, Set<String> unresolved, int replaced) {
    }

    /**
     * Swaps each handle for the person's CURRENT name. {@code currentVisible} is read fresh by the caller: a handle
     * resolves only if its person is in that list now (name-only people resolve from what this session already saw).
     */
    Revealed reveal(String text, List<Person> currentVisible) {
        Map<String, Person> now = new HashMap<>();
        for (Person person : currentVisible == null ? List.<Person>of() : currentVisible) {
            if (person != null && person.name() != null) now.put(person.key(), person);
        }
        Set<String> unresolved = new LinkedHashSet<>();
        int[] replaced = {0};
        Function<String, String> resolve = handle -> {
            String key = table.keyFor(handle);
            if (key == null) return null;
            Person person = now.get(key);
            if (person != null) return person.name();
            // A name seen only as text (a manager outside the review scope) has no live entry to check against;
            // it was shown to this actor in this session by an authorized tool, so its remembered name stands.
            return key.startsWith("name:") ? table.lastKnownName(key) : null;
        };
        Matcher matcher = HANDLE.matcher(text == null ? "" : text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String handle = "P" + matcher.group(1);
            String name = resolve.apply(handle);
            if (name == null) {
                unresolved.add(handle);
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                replaced[0]++;
                matcher.appendReplacement(out, Matcher.quoteReplacement(name));
            }
        }
        matcher.appendTail(out);
        return new Revealed(out.toString(), unresolved, replaced[0]);
    }

    static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Distinct normalized names, for tests. */
    Set<String> knownNames() {
        Set<String> names = new HashSet<>();
        for (Candidate candidate : candidates) names.add(normalize(candidate.person().name()));
        return names;
    }
}
