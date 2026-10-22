package com.launchcatch.global.query;

import java.util.Objects;

public final class LikePatternEscaper {

    public static final char ESCAPE_CHARACTER = '!';

    private LikePatternEscaper() {
    }

    public static String escapeLiteral(String input) {
        Objects.requireNonNull(input, "input must not be null");

        return input
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
