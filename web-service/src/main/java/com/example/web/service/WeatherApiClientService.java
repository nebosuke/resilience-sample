package com.example.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.vavr.control.Try;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Map;
import java.util.function.Supplier;

/**
 * weather-service を呼び出すクライアント
 */
@Slf4j
@Service
public class WeatherApiClientService {

    private static final String AREA_JSON_ENDPOINT = "http://weather-service:9702/api/v1/weather/areas";

    private static final String FORECAST_OVERVIEW_JSON_ENDPOINT = "http://weather-service:9702/api/v1/weather/{0}";

    // TCP接続が確立するまでの時間におけるタイムアウト
    private static final long CONNECT_TIMEOUT_MILLIS = 3000;

    // HttpClientの接続プールから接続を取得する際のタイムアウト
    private static final long CONNECTION_REQUEST_TIMEOUT_MILLIS = 1000;

    // エンドポイントからレスポンスが送信され始めるまでのタイムアウト
    private static final long RESPONSE_TIMEOUT_MILLIS = 10_000;

    // Apache HttpClient の各種タイムアウト設定を作成する
    private static final RequestConfig HTTP_REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MILLIS))
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECTION_REQUEST_TIMEOUT_MILLIS))
            .setResponseTimeout(Timeout.ofMilliseconds(RESPONSE_TIMEOUT_MILLIS))
            .build();

    private final ResilienceController resilienceController;

    public WeatherApiClientService(ResilienceController resilienceController) {
        this.resilienceController = resilienceController;
    }

    @Data
    public static final class ForecastOverviewResponse implements Serializable {

        private int statusCode;

        private String reportDateTime;

        private String text; // "関東甲信地方は、・・・"
    }

    /**
     * 指定したエリアの天気予報データを取得する
     *
     * <p>
     * weather-service の /api/v1/weathre/{area} を呼び出す。<br>
     * weather-service の側は、不安定なサービスをシミュレートしてランダムに遅延やサーバーエラーが発生する。<br>
     * Resilience4j を利用してこの影響を受けないように対策している。
     * </p>
     * @param area
     * @return
     */
    @WithSpan
    public ForecastOverviewResponse getForecastOverview(String area) {
        Retry retry = resilienceController.getRetryRegistry().retry("fetchForecastOverview");

        Supplier<ForecastOverviewResponse> supplier = Decorators.ofSupplier(() -> fetchForecastOverview(area))
                .withRetry(retry)
                .decorate();

        return Try.ofSupplier(supplier).recover(throwable -> null).get();
    }

    @WithSpan
    private ForecastOverviewResponse fetchForecastOverview(String area) {
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(HTTP_REQUEST_CONFIG) // タイムアウトを設定する
                .build()) {
            return client.execute(new HttpGet(MessageFormat.format(FORECAST_OVERVIEW_JSON_ENDPOINT, area)),
                    response -> {
                        if (HttpStatus.SC_SUCCESS == response.getCode()) {
                            HttpEntity entity = response.getEntity();
                            String json = EntityUtils.toString(entity);
                            return new ObjectMapper().readValue(json, ForecastOverviewResponse.class);
                        }
                        return null;
                    });
        } catch (IOException e) {
            log.error("an error occured while fetching forecast overview", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 利用可能な気象台エリアコードの一覧を取得する
     *
     * <p>
     * weather-service の /api/v1/weather/areas を呼び出す。
     * weather-service の側は、不安定なサービスをシミュレートしてランダムに遅延やサーバーエラーが発生する。<br>
     * Resilience4j を利用してこの影響を受けないように対策している。
     * </p>
     * @return
     */
    @WithSpan
    public Map<String, String> getAvailableAreas() {
        Retry retry = resilienceController.getRetryRegistry().retry("fetchAvailableAreas");

        Supplier<Map<String, String>> supplier = Decorators.ofSupplier(() -> fetchAvailableAreas())
                .withRetry(retry)
                .decorate();

        return Try.ofSupplier(supplier).recover(throwable -> null).get();
    }

    @WithSpan
    private Map<String, String> fetchAvailableAreas() {
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(HTTP_REQUEST_CONFIG) // タイムアウトを設定する
                .build()) {
            return client.execute(new HttpGet(AREA_JSON_ENDPOINT), response -> {
                if (HttpStatus.SC_SUCCESS == response.getCode()) {
                    HttpEntity entity = response.getEntity();
                    String json = EntityUtils.toString(entity);
                    return new ObjectMapper().readValue(json, new TypeReference<Map<String, String>>() {
                    });
                }
                return null;
            });
        } catch (IOException e) {
            log.error("an error occured while fetching available areas", e);
            throw new RuntimeException(e);
        }
    }
}
