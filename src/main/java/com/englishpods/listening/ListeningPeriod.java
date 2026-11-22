package com.englishpods.listening;

public record ListeningPeriod(long start, long end) {
    long overlap(long from, long to) {
        return Math.max(0, Math.min(to, end) - Math.max(from, start));
    }
}
