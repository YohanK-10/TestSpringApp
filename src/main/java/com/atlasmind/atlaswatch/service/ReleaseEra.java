package com.atlasmind.atlaswatch.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

enum ReleaseEra {
    ANY("any", "Any era", Integer.MIN_VALUE, Integer.MAX_VALUE),
    PRE_1980("pre-1980", "Before 1980", Integer.MIN_VALUE, 1979),
    EIGHTIES("1980s", "1980s", 1980, 1989),
    NINETIES("1990s", "1990s", 1990, 1999),
    TWO_THOUSANDS("2000s", "2000s", 2000, 2009),
    TWENTY_TENS("2010s", "2010s", 2010, 2019),
    TWENTY_TWENTIES("2020s", "2020s", 2020, 2029);

    private static final Map<String, ReleaseEra> LOOKUP = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ReleaseEra::value, era -> era));

    private final String value;
    private final String displayLabel;
    private final int startYear;
    private final int endYear;

    ReleaseEra(String value, String displayLabel, int startYear, int endYear) {
        this.value = value;
        this.displayLabel = displayLabel;
        this.startYear = startYear;
        this.endYear = endYear;
    }

    String value() {
        return value;
    }

    String displayLabel() {
        return displayLabel;
    }

    boolean matches(LocalDate releaseDate) {
        if (this == ANY) {
            return true;
        }
        if (releaseDate == null) {
            return false;
        }
        int year = releaseDate.getYear();
        return year >= startYear && year <= endYear;
    }

    static Set<ReleaseEra> from(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of(ANY);
        }

        Set<ReleaseEra> resolved = values.stream()
                .map(ReleaseEra::fromSingle)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (resolved.size() > 1) {
            resolved.remove(ANY);
        }
        return resolved.isEmpty() ? Set.of(ANY) : resolved;
    }

    static boolean hasIntent(Set<ReleaseEra> eras) {
        return eras != null && !eras.isEmpty() && !(eras.size() == 1 && eras.contains(ANY));
    }

    private static ReleaseEra fromSingle(String rawValue) {
        String normalized = rawValue == null ? "any" : rawValue.trim().toLowerCase(Locale.ROOT);
        ReleaseEra era = LOOKUP.get(normalized);
        if (era == null) {
            throw new IllegalArgumentException("Invalid release era: '" + rawValue + "'.");
        }
        return era;
    }
}
