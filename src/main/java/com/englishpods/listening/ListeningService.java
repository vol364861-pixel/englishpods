package com.englishpods.listening;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ListeningService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FIRST_WEEK = LocalDate.of(2026, 11, 16);
    private final ListeningRepository repository;

    public ListeningService(ListeningRepository repository) {
        this.repository = repository;
    }

    public void record(List<ListeningPeriod> periods) {
        long latestEnd = Instant.now().plusSeconds(5).toEpochMilli();
        if (periods == null || periods.isEmpty() || periods.size() > 100 || periods.stream().anyMatch(period ->
                period == null || period.start() < 0 || period.end() <= period.start()
                        || period.end() > latestEnd || period.end() - period.start() > 86_400_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listening periods.");
        }
        repository.merge(periods);
    }

    public Summary summary(Integer week, Instant now) {
        LocalDate today = now.atZone(BEIJING).toLocalDate();
        int currentWeek = (int) Math.max(1, ChronoUnit.WEEKS.between(FIRST_WEEK, today) + 1);
        int shownWeek = week == null ? currentWeek : week;
        if (shownWeek < 1 || shownWeek > currentWeek) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listening week.");
        }
        LocalDate first = FIRST_WEEK.plusWeeks(shownWeek - 1L);
        var periods = repository.forSummary(midnight(first), midnight(first.plusDays(7)),
                midnight(today), midnight(today.plusDays(1)));
        var days = IntStream.range(0, 7).mapToObj(offset -> dailyTotal(first.plusDays(offset), periods)).toList();
        return new Summary(shownWeek, currentWeek, dailyTotal(today, periods), days);
    }

    private Day dailyTotal(LocalDate date, List<ListeningPeriod> periods) {
        long start = midnight(date);
        long end = midnight(date.plusDays(1));
        return new Day(date.toString(), periods.stream().mapToLong(period -> period.overlap(start, end)).sum());
    }

    private long midnight(LocalDate date) {
        return date.atStartOfDay(BEIJING).toInstant().toEpochMilli();
    }

    public record Day(String date, long milliseconds) {}
    public record Summary(int week, int currentWeek, Day today, List<Day> days) {}
}
