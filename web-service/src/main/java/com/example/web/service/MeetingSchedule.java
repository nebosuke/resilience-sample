package com.example.web.service;

import lombok.Value;

import java.time.Instant;

@Value
public class MeetingSchedule {

    private final String id;

    private final String title;

    private final Instant beginDateTime; // inclusive

    private final Instant endDateTime; // exclusive

    private final String area;
}
