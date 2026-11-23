package com.waypoint.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict argument schemas for Wavi tools, and their validation. A schema accepts only the arguments it declares
 * ({@code additionalProperties: false}). Each argument is an enum value, an ISO date, an ISO month or a bounded
 * integer; free text is never accepted, so nothing a router writes is echoed into a reply or used as a query.
 *
 * <p>Dates and months must also fall inside a window around today. A JSON schema cannot express that bound, so it
 * is enforced here, and {@link Schema#toJsonSchema()} leaves it out.
 */
final class WaviToolArguments {

    private WaviToolArguments() {
    }

    /** At most this many arguments in one decision, whatever the schema says. */
    // The team-timesheet contract needs its status, person ordinal and one compact typed period selector.
    // Every value remains schema-bound and identity arguments remain prohibited.
    static final int MAX_ARGUMENTS = 9;
    private static final int MAX_VALUE_LENGTH = 40;

    /**
     * Argument names that would let a caller choose whose data is read. No schema may declare one, and the gate
     * rejects any decision that contains one: identity comes only from the verified actor.
     */
    private static final Set<String> IDENTITY_NAMES = Set.of("user", "userid", "users", "employee", "employeeid",
            "empid", "employeenumber", "staffid", "person", "personid", "subject", "subjectid", "target", "targetuser",
            "targetuserid", "organization", "organizationid", "organisation", "organisationid", "org", "orgid",
            "company", "companyid", "tenant", "tenantid", "role", "roles", "actor", "actorid", "email", "login",
            "loginid", "username", "manager", "managerid", "reportingmanagerid", "owner", "ownerid", "account",
            "accountid");

    static boolean isIdentityName(String name) {
        if (name == null) return false;
        StringBuilder letters = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') letters.append(c);
        }
        return IDENTITY_NAMES.contains(letters.toString());
    }

    enum Type { ENUM, DATE, DATE_RANGE, MONTH, INTEGER }

    /** A bounded inclusive pair of ISO dates. The wire form is {@code yyyy-MM-dd/yyyy-MM-dd}. */
    record DateRange(LocalDate from, LocalDate to) {
        DateRange {
            if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("Invalid date range");
        }

        @Override public String toString() { return from + "/" + to; }
    }

    /**
     * One argument. For DATE, {@code min}/{@code max} are days relative to today. For MONTH they are months
     * relative to the current month. For INTEGER they are the inclusive bounds.
     */
    record Spec(String name, Type type, boolean required, List<String> values, int min, int max, String description) {

        Spec {
            if (name == null || name.isEmpty() || name.length() > 30) {
                throw new IllegalArgumentException("Invalid argument name");
            }
            for (char c : name.toCharArray()) {
                if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')) {
                    throw new IllegalArgumentException("Argument names are letters and digits only: " + name);
                }
            }
            if (isIdentityName(name)) {
                throw new IllegalArgumentException("A tool may not take an identity argument: " + name);
            }
            values = values == null ? List.of() : List.copyOf(values);
            if (type == Type.ENUM && values.isEmpty()) throw new IllegalArgumentException("Enum without values: " + name);
            if (min > max) throw new IllegalArgumentException("Empty range for " + name);
            description = description == null ? "" : description;
        }

        static Spec enumOf(String name, boolean required, Collection<String> values, String description) {
            return new Spec(name, Type.ENUM, required, List.copyOf(values), 0, 0, description);
        }

        static <E extends Enum<E>> Spec enumOf(String name, boolean required, Class<E> type, String description) {
            List<String> names = new ArrayList<>();
            for (E constant : type.getEnumConstants()) names.add(constant.name());
            return enumOf(name, required, names, description);
        }

        /** An ISO date (yyyy-MM-dd) no more than {@code pastDays} before and {@code futureDays} after today. */
        static Spec date(String name, boolean required, int pastDays, int futureDays, String description) {
            return new Spec(name, Type.DATE, required, List.of(), -pastDays, futureDays, description);
        }

        /** An inclusive ISO range encoded as {@code yyyy-MM-dd/yyyy-MM-dd}, bounded independently at both ends. */
        static Spec dateRange(String name, boolean required, int pastDays, int futureDays, String description) {
            return new Spec(name, Type.DATE_RANGE, required, List.of(), -pastDays, futureDays, description);
        }

        /** An ISO month (yyyy-MM) no more than {@code pastMonths} before and {@code futureMonths} after this one. */
        static Spec month(String name, boolean required, int pastMonths, int futureMonths, String description) {
            return new Spec(name, Type.MONTH, required, List.of(), -pastMonths, futureMonths, description);
        }

        static Spec integer(String name, boolean required, int min, int max, String description) {
            return new Spec(name, Type.INTEGER, required, List.of(), min, max, description);
        }
    }

    /** A tool's complete argument list. */
    record Schema(List<Spec> specs) {

        static final Schema NONE = new Schema(List.of());

        Schema {
            specs = List.copyOf(specs);
            if (specs.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("Too many arguments");
            Set<String> names = new HashSet<>();
            for (Spec spec : specs) {
                if (!names.add(spec.name())) throw new IllegalArgumentException("Duplicate argument " + spec.name());
            }
        }

        static Schema of(Spec... specs) {
            return new Schema(List.of(specs));
        }

        /**
         * The JSON schema a structured-output router is given: an object with exactly these properties, enums
         * listed, no additional properties. Date windows and integer bounds are enforced by {@link #validate}.
         */
        Map<String, Object> toJsonSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Spec spec : specs) {
                Map<String, Object> property = new LinkedHashMap<>();
                switch (spec.type()) {
                    case ENUM -> {
                        property.put("type", "string");
                        property.put("enum", spec.values());
                    }
                    case DATE, DATE_RANGE -> {
                        property.put("type", "string");
                        if (spec.type() == Type.DATE) property.put("format", "date");
                    }
                    case MONTH -> property.put("type", "string");
                    case INTEGER -> property.put("type", "integer");
                }
                if (!spec.description().isEmpty()) property.put("description", spec.description());
                properties.put(spec.name(), Collections.unmodifiableMap(property));
                if (spec.required()) required.add(spec.name());
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Collections.unmodifiableMap(properties));
            schema.put("required", List.copyOf(required));
            schema.put("additionalProperties", false);
            return Collections.unmodifiableMap(schema);
        }

        /** The explicit "not given" value of an optional argument in the router schema: never a valid real value. */
        static final String NOT_GIVEN = "UNSPECIFIED";

        private static boolean isNotGiven(Spec spec, Object value) {
            return switch (spec.type()) {
                case ENUM -> NOT_GIVEN.equals(value);
                case DATE, DATE_RANGE, MONTH -> "".equals(value);
                case INTEGER -> value instanceof Number number && number.longValue() == 0 && spec.min() > 0;
            };
        }

        /**
         * The schema a structured-output router is given. Structured outputs compile the schema into a grammar whose
         * size roughly doubles with each optional parameter, and the API rejects grammars that are too large (400,
         * "Schema is too complex for compilation"). So every argument is REQUIRED here, and an optional one has an
         * explicit "not given" value instead: UNSPECIFIED (choices), "" (dates and months; no format, which "" would
         * fail) or 0 (counts, whose real values start at 1). {@link #validate} treats those as absent; the tool
         * schema, its validation and every authorization check are unchanged.
         */
        Map<String, Object> toRouterJsonSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Spec spec : specs) {
                Map<String, Object> property = new LinkedHashMap<>();
                String notGiven = "";
                switch (spec.type()) {
                    case ENUM -> {
                        property.put("type", "string");
                        List<String> values = new ArrayList<>(spec.values());
                        if (!spec.required()) {
                            values.add(NOT_GIVEN);
                            notGiven = " " + NOT_GIVEN + " when not given.";
                        }
                        property.put("enum", List.copyOf(values));
                    }
                    case DATE, DATE_RANGE -> {
                        property.put("type", "string");
                        String format = spec.type() == Type.DATE ? "YYYY-MM-DD" : "YYYY-MM-DD/YYYY-MM-DD";
                        notGiven = spec.required() ? " " + format + "." : " " + format + ", or \"\" when not given.";
                        if (spec.required() && spec.type() == Type.DATE) property.put("format", "date");
                    }
                    case MONTH -> {
                        property.put("type", "string");
                        notGiven = spec.required() ? " YYYY-MM." : " YYYY-MM, or \"\" when not given.";
                    }
                    case INTEGER -> {
                        property.put("type", "integer");
                        notGiven = spec.required() || spec.min() <= 0 ? "" : " 0 when not given.";
                    }
                }
                String description = (spec.description() + notGiven).strip();
                if (!description.isEmpty()) property.put("description", description);
                properties.put(spec.name(), Collections.unmodifiableMap(property));
                required.add(spec.name());
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Collections.unmodifiableMap(properties));
            schema.put("required", List.copyOf(required));
            schema.put("additionalProperties", false);
            return Collections.unmodifiableMap(schema);
        }

        /** Checks raw arguments; never throws for bad input. */
        Validation validate(Map<String, ?> raw, LocalDate today) {
            Map<String, ?> input = raw == null ? Map.of() : raw;
            if (input.size() > MAX_ARGUMENTS) return Validation.problem("TOO_MANY_ARGUMENTS");
            Map<String, Spec> byName = new LinkedHashMap<>();
            specs.forEach(spec -> byName.put(spec.name(), spec));
            for (Object key : input.keySet()) {
                if (!(key instanceof String name)) return Validation.problem("INVALID_ARGUMENT_NAME");
                if (isIdentityName(name)) return Validation.problem("IDENTITY_ARGUMENT");
                if (!byName.containsKey(name)) return Validation.problem("UNKNOWN_ARGUMENT");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Spec spec : specs) {
                Object value = input.get(spec.name());
                // The router schema makes every argument required (structured-output grammar size); an optional
                // argument it marks "not given" is treated exactly as if it were absent.
                if (!spec.required() && isNotGiven(spec, value)) continue;
                if (value == null) {
                    if (input.containsKey(spec.name())) return Validation.problem("NULL_ARGUMENT");
                    if (spec.required()) return Validation.problem("MISSING_ARGUMENT");
                    continue;
                }
                Object parsed = parse(spec, value, today);
                if (parsed == null) return Validation.problem("INVALID_ARGUMENT");
                values.put(spec.name(), parsed);
            }
            return new Validation(new Validated(values), null);
        }

        private static Object parse(Spec spec, Object value, LocalDate today) {
            return switch (spec.type()) {
                case ENUM -> value instanceof String text && spec.values().contains(text) ? text : null;
                case INTEGER -> {
                    if (!(value instanceof Integer) && !(value instanceof Long) && !(value instanceof Short)
                            && !(value instanceof Byte)) yield null;
                    long number = ((Number) value).longValue();
                    yield number >= spec.min() && number <= spec.max() ? (int) number : null;
                }
                case DATE -> {
                    if (!(value instanceof String text) || text.length() != 10 || today == null) yield null;
                    try {
                        LocalDate date = LocalDate.parse(text);
                        yield date.isBefore(today.plusDays(spec.min())) || date.isAfter(today.plusDays(spec.max()))
                                ? null : date;
                    } catch (DateTimeParseException invalid) {
                        yield null;
                    }
                }
                case DATE_RANGE -> {
                    if (!(value instanceof String text) || text.length() != 21 || today == null || text.charAt(10) != '/') yield null;
                    try {
                        LocalDate from = LocalDate.parse(text.substring(0, 10));
                        LocalDate to = LocalDate.parse(text.substring(11));
                        yield from.isBefore(today.plusDays(spec.min())) || from.isAfter(today.plusDays(spec.max()))
                                || to.isBefore(today.plusDays(spec.min())) || to.isAfter(today.plusDays(spec.max()))
                                || from.isAfter(to) ? null : new DateRange(from, to);
                    } catch (DateTimeException invalid) {
                        yield null;
                    }
                }
                case MONTH -> {
                    if (!(value instanceof String text) || text.length() != 7 || today == null) yield null;
                    try {
                        YearMonth month = YearMonth.parse(text);
                        YearMonth current = YearMonth.from(today);
                        yield month.isBefore(current.plusMonths(spec.min())) || month.isAfter(current.plusMonths(spec.max()))
                                ? null : month;
                    } catch (DateTimeException invalid) {
                        yield null;
                    }
                }
            };
        }
    }

    /** Either validated arguments or a problem code (never both). */
    record Validation(Validated arguments, String problem) {
        static Validation problem(String code) {
            return new Validation(null, code);
        }

        boolean valid() {
            return problem == null;
        }
    }

    /** Arguments that passed their schema, already parsed into their types. */
    static final class Validated {

        static final Validated EMPTY = new Validated(Map.of());

        private final Map<String, Object> values;

        Validated(Map<String, Object> values) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        boolean has(String name) {
            return values.containsKey(name);
        }

        String text(String name) {
            Object value = values.get(name);
            return value instanceof String text && text.length() <= MAX_VALUE_LENGTH ? text : null;
        }

        <E extends Enum<E>> E enumValue(String name, Class<E> type) {
            String text = text(name);
            if (text == null) return null;
            try {
                return Enum.valueOf(type, text);
            } catch (IllegalArgumentException notThisEnum) {
                return null;
            }
        }

        LocalDate date(String name) {
            return values.get(name) instanceof LocalDate date ? date : null;
        }

        DateRange dateRange(String name) {
            return values.get(name) instanceof DateRange range ? range : null;
        }

        YearMonth month(String name) {
            return values.get(name) instanceof YearMonth month ? month : null;
        }

        Integer integer(String name) {
            return values.get(name) instanceof Integer number ? number : null;
        }

        Set<String> names() {
            return values.keySet();
        }

        /** Scalar canonical state, with optional sentinels removed and dates encoded independently of Jackson modules. */
        Map<String, Object> routingValues() {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(key,
                    value instanceof LocalDate || value instanceof DateRange || value instanceof YearMonth ? value.toString() : value));
            return Collections.unmodifiableMap(result);
        }

        @Override
        public String toString() {
            // Names only: argument values never go to logs.
            return "Validated" + values.keySet();
        }
    }
}
