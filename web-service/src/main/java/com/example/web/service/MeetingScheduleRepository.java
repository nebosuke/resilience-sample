package com.example.web.service;

import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class MeetingScheduleRepository {

    private final WeatherApiClientService weatherApiClientService;

    public MeetingScheduleRepository(WeatherApiClientService weatherApiClientService) {
        this.weatherApiClientService = weatherApiClientService;
    }

    /**
     * テスト用にダミーのミーティングスケジュールをランダムに生成する
     *
     * <p>
     * 本当のシステムではDBをバックエンドにして MeetingSchedule を生成するが、このサンプルでは適当にランダムなミーティングスケジュールを生成する。<br>
     * 呼び出しにはレジリエンスプログラミングの効果を見るため、DBの遅延をシミュレートしてランダムな遅延時間が発生する。
     * </p>
     * @return
     */
    public List<MeetingSchedule> findDummyMeetings() {
        List<MeetingSchedule> meetings = new ArrayList<>();

        List<String> availableAreas = new ArrayList<>(weatherApiClientService.getAvailableAreas().keySet());

        int num = RandomUtils.nextInt(0, 8) + 1;
        for (int i = 0; i < num; i++) {
            String area = availableAreas.get(RandomUtils.nextInt(0, availableAreas.size()));
            meetings.add(makeDummyMeeting(i, area));
        }

        // 0〜500ミリ秒の間でランダムに sleep する
        try {
            Thread.sleep(RandomUtils.nextLong(0, 1000));
        } catch (InterruptedException ignore) {
        }

        return meetings;
    }

    private MeetingSchedule makeDummyMeeting(int index, String area) {
        String id = UUID.randomUUID().toString();
        String title = "ダミー会議-" + index;

        Date beginDateTime = DateUtils.truncate(
                new Date(System.currentTimeMillis() + RandomUtils.nextLong(0, 86400_000)),
                Calendar.HOUR_OF_DAY);
        Date endDateTime = DateUtils.addMinutes(beginDateTime, 30);

        return new MeetingSchedule(id, title, beginDateTime.toInstant(), endDateTime.toInstant(), area);
    }

}
