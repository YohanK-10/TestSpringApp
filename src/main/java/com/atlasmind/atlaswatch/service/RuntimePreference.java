package com.atlasmind.atlaswatch.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

public enum RuntimePreference {
    ANY("any", runtime -> true),
    SHORT("short", runtime -> runtime <= 105),
    MEDIUM("medium", runtime -> runtime > 105 && runtime <= 135),
    LONG("long", runtime -> runtime > 135);

    private static final Map<String, RuntimePreference> LOOKUP = new HashMap<>();

    static {
        for (RuntimePreference preference : values()) {
            LOOKUP.put(preference.label, preference);
        }
    }

    private final String label;
    private final Predicate<Integer> matcher;

    RuntimePreference(String label, Predicate<Integer> matcher) {
        this.label = label;
        this.matcher = matcher;
    }

    private boolean matches(int runtime) {
        return matcher.test(runtime);
    }

    public String label() {
        return label;
    }

    public boolean passesHardFilter(Integer runtime) {
        if (runtime == null) {
            return false;
        }

        return switch (this) {
            case ANY -> true;
            case SHORT -> runtime <= 125;
            case MEDIUM -> runtime >= 90 && runtime <= 150;
            case LONG -> runtime >= 120;
        };
    }

    public double score(Integer runtime) {
        if (runtime == null || this == ANY) {
            return 0.0;
        }

        if (matches(runtime)) {
            return 1.0;
        }

        if (!passesHardFilter(runtime)) {
            return 0.0;
        }

        return 0.4;
    }

    public static RuntimePreference from(String value) {
        if (value == null || value.isBlank()) {
            return ANY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        RuntimePreference preference = LOOKUP.get(normalized);
        if (preference == null) {
            throw new IllegalArgumentException(
                    "Invalid runtimePreference: '" + value + "'. Must be any, short, medium, or long."
            );
        }
        return preference;
    }
}
