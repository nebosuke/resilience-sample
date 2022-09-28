package com.example.weather.controller;

import com.example.weather.service.ResilienceController;
import com.example.weather.service.WeatherDataService;
import io.github.resilience4j.timelimiter.TimeLimiter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@RestController
@Slf4j
public class WeatherApiController {

    private final ResilienceController resilienceController;

    private final WeatherDataService weatherDataService;

    private final ThreadPoolTaskExecutor ioThread;

    public WeatherApiController(
            ResilienceController resilienceController,
            WeatherDataService weatherDataService,
            ThreadPoolTaskExecutor ioThread
    ) {
        this.resilienceController = resilienceController;
        this.weatherDataService = weatherDataService;
        this.ioThread = ioThread;
    }

    /**
     * APIのレスポンスとなる型
     */
    @Value
    public class ApiResponse {

        public static final int STATUS_OK = 1;

        public static final int STATUS_ERR = 0;

        private final int statusCode;

        private final String reportDateTime;

        private final String text;
    }

    @RequestMapping(method = RequestMethod.GET, path = "/api/v1/weather/{area}")
    public ApiResponse getWeatherForecast(@PathVariable("area") String area) {

        // レジリエンスプログラミングの効果を体感するため、ランダムな遅延時間と一定の確率でサーバーエラーを発生させる
        simulateUnstableService();

        // getForecastOverview() はネットワーク通信を伴うため、場合によっては長い時間がかかる。
        // TimeLimiter を使って最大で1000ミリ秒でタイムアウトするようにガードして実行する。
        TimeLimiter timeLimiter = resilienceController.getTimeLimiterRegistry()
                .timeLimiter("getWeatherForecast");

        WeatherDataService.ForecastOverview forecastOverview = null;
        try {
            // 取得自体はスレッドプールを用いて、APIのリクエストを処理するスレッドとは別に実行する
            forecastOverview = timeLimiter.executeFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> weatherDataService.getForecastOverview(area),
                            ioThread));
        } catch (TimeoutException e) {
            // タイムアウトしたとき TimeoutException が発生する
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); // 503
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR); // 500
        }

        return new ApiResponse(ApiResponse.STATUS_OK, forecastOverview.getReportDatetime(),
                forecastOverview.getText());
    }

    @RequestMapping(method = RequestMethod.GET, path = "/api/v1/weather/areas")
    public Map<String, String> getAvailableAreas() {

        // レジリエンスプログラミングの効果を体感するため、ランダムな遅延時間と一定の確率でサーバーエラーを発生させる
        simulateUnstableService();

        // getCachedAvailableAreas() はネットワーク通信を伴うため、場合によっては長い時間がかかる。
        // TimeLimiter を使って最大で1000ミリ秒でタイムアウトするようにガードして実行する。
        TimeLimiter timeLimiter = resilienceController.getTimeLimiterRegistry()
                .timeLimiter("getAvailableAreas");
        try {
            // 取得自体はスレッドプールを用いて、APIのリクエストを処理するスレッドとは別に実行する
            return timeLimiter.executeFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> weatherDataService.getCachedAvailableAreas(),
                            ioThread));
        } catch (TimeoutException e) {
            // タイムアウトしたとき TimeoutException が発生する
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); // 503
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR); // 500
        }
    }

    /**
     * レジリエンスプログラミングの効果を実感するため不安定なサービスをシミュレートする
     *
     * @throws ResponseStatusException サーバーエラーとするとき 500 Internal Server Error を発生させる
     */
    private void simulateUnstableService() {
        if (RandomUtils.nextInt(0, 5) == 0) {
            // 1/5 の確率で 500 Internal Server Error が発生する
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR); // 500
        }
    }
}
