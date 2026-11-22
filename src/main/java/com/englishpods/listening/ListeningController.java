package com.englishpods.listening;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/listening")
public class ListeningController {
    private final ListeningService service;

    public ListeningController(ListeningService service) {
        this.service = service;
    }

    @GetMapping
    public ListeningService.Summary summary(@RequestParam(required = false) Integer week) {
        return service.summary(week, Instant.now());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@RequestBody List<ListeningPeriod> periods) {
        service.record(periods);
    }
}
