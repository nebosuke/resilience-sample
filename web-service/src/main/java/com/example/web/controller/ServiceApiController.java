package com.example.web.controller;

import com.example.web.service.MeetingSchedule;
import com.example.web.service.MeetingScheduleRepository;
import com.example.web.service.WeatherApiClientService;
import lombok.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
public class ServiceApiController {

    private final MeetingScheduleRepository meetingScheduleRepository;

    private final WeatherApiClientService weatherApiClientService;

    public ServiceApiController(
            MeetingScheduleRepository meetingScheduleRepository,
            WeatherApiClientService weatherApiClientService
    ) {
        this.meetingScheduleRepository = meetingScheduleRepository;
        this.weatherApiClientService = weatherApiClientService;
    }

    @Value
    private final class MeetingScheduleWithWeatherForecast {

        private final String id;

        private final String title;

        private final Instant beginDateTime;

        private final Instant endDateTime;

        private final String weatherForecast;

    }

    /**
     * テスト用のエンドポイント
     * @return
     */
    @RequestMapping(method = RequestMethod.GET, path = "/api/v1/meetings")
    public List<MeetingScheduleWithWeatherForecast> getWeatherForecast() {

        // ミーティングの一覧を取得する
        List<MeetingSchedule> meetings = meetingScheduleRepository.findDummyMeetings();

        // ミーティングの開催エリアの天気予報を取得してレスポンスを生成する
        return meetings.stream().map(schedule -> {

            WeatherApiClientService.ForecastOverviewResponse forecastOverview = weatherApiClientService.getForecastOverview(
                    schedule.getArea());

            return new MeetingScheduleWithWeatherForecast(schedule.getId(), schedule.getTitle(),
                    schedule.getBeginDateTime(), schedule.getEndDateTime(),
                    forecastOverview != null ? forecastOverview.getText() : "");
        }).collect(Collectors.toList());
    }
}
