package com.example.weather.service;

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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 天気予報データのデータソースとなるサービス
 *
 * <p>
 * 一般的なサービスではDBなどのデータソースを参照したビジネスロジック層として構築するが、データがないので、
 * 代替として、気象庁のWebページで利用可能な天気予報のJSONを取得するエンドポイントをデータソースとして利用して天気予報データを提供する。<br>
 * </p>
 * <pre>
 * &gt; 仕様の継続性や運用状況のお知らせを気象庁はお約束していないという意味で、
 * &gt; APIではないと申し上げざるを得ないのですが、
 * &gt; 一方で政府標準利用規約に準拠してご利用いただけます。
 * <a href="https://twitter.com/e_toyoda/status/1364504338572410885">https://twitter.com/e_toyoda/status/1364504338572410885</a>
 * </pre>
 * <p>
 * このクラスでは、一度取得した結果を60秒間キャッシュする。この時間は ehcache.xml で定義している。
 * </p>
 */
@Service
@Slf4j
public class WeatherDataService {

    /**
     * 気象台のコードが含まれるエリア定義のJSONを取得するエンドポイント
     */
    private static final String AREA_JSON_ENDPOINT = "https://www.jma.go.jp/bosai/common/const/area.json";

    /**
     * 天気予報のJSONデータを取得するエンドポイント
     *
     * MessageFormat を用いてフォーマットされ、<code>{0}</code>の部分はエリアコードで置換される。
     */
    private static final String FORECAST_OVERVIEW_ENDPOINT = "https://www.jma.go.jp/bosai/forecast/data/overview_forecast/{0}.json";

    @Data
    public static final class ForecastOverview implements Serializable {

        private String publishingOffice; // "気象庁"

        private String reportDatetime; // "2022-09-28T04:42:00+09:00"

        private String targetArea; // "東京都"

        private String headlineText; // ""

        private String text; // "関東甲信地方は、・・・"
    }

    // TLS接続が確立するまでの時間におけるタイムアウト
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

    public WeatherDataService(ResilienceController resilienceController) {
        this.resilienceController = resilienceController;
    }

    /**
     * エンドポイントにアクセスした結果のJSONデータを取得する
     *
     * <p>
     * Cacheableアノテーションによって、結果は ehcache で一定時間キャッシュされる。<br>
     * </p>
     * @param area 気象台のコード（例: 130000）
     * @return
     */
    @Cacheable(value = "forecastOverview", key = "#area")
    @WithSpan
    public ForecastOverview getForecastOverview(String area) {
        // Cacheable がセットされているのでこのコードはキャッシュで解決できない場合にしか呼び出されない

        if (!isAvailableArea(area)) {
            log.info("unsupported area code: {}", area);
            return null;
        }

        // Resilience4j の Retry を利用してエラー時にはリトライするようにする
        Retry retry = resilienceController.getRetryRegistry().retry("forecastOverview");

        // Try.ofSupplier での呼び出しによりエラー時には最大3回のリトライが実行される
        return Try.ofSupplier(
                        Decorators.ofSupplier(() -> fetchForecastOverview(area)).withRetry(retry).decorate())
                .recover(throwable -> null)
                .get();
    }

    /**
     * 予報JSONエンドポイントに接続して結果を取得する
     *
     * @param area
     * @return
     * @throws IOException
     */
    private ForecastOverview fetchForecastOverview(String area) {
        String endpointUrl = MessageFormat.format(FORECAST_OVERVIEW_ENDPOINT, area);
        log.info("fetch forecast overview from {}", endpointUrl);

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(HTTP_REQUEST_CONFIG) // タイムアウトを設定する
                .build()) {
            return client.execute(new HttpGet(endpointUrl), response -> {

                // 200 のときはJSONをデコードして ForecastOverview のインスタンスを返す
                if (HttpStatus.SC_SUCCESS == response.getCode()) {
                    HttpEntity entity = response.getEntity();
                    String json = EntityUtils.toString(entity);
                    return new ObjectMapper().readValue(json, ForecastOverview.class);
                }

                // 404 や 503 のときは null を返す。
                // Resilience4j の設定によって、null のときは待ち時間を入れてリトライする。
                log.info("forecast overview endpoint returns {}", response.toString());

                return null;
            });
        } catch (IOException e) {
            log.error("an error occured while fetching forecast overview", e);
            throw new RuntimeException(e);
        }
    }

    @Data
    private final class CachedAvailableAreas {

        // (011000, "宗谷地方")
        private final Map<String, String> availableAreas;

        private final long createdAt = System.currentTimeMillis();

        private boolean isAlive() {
            return createdAt > (System.currentTimeMillis() - 86_400_000L);
        }
    }

    // 利用可能な気象台コードのセットをキャッシュしてスレッド間のロックなしに共有するため ConcurrentMap を利用する。
    // キーが 0 の値として Future<CachedAvailableAreas> を保持する。
    // 0以外のキーは存在しない。
    private final ConcurrentMap<Integer, Future<CachedAvailableAreas>> cachedAvailableAreasContainer = new ConcurrentHashMap<>();

    /**
     * area が利用可能なコードかどうかを判定する
     *
     * <p>
     * このメソッドは利用可能なコードのデータが存在しない場合はネットワークに問い合わせる。<br>
     * つまり実行に長い時間がかかる可能性がある。<br>
     * 呼び出し側ではタイムリミッターをセットするなどブロッキングを防止しなければならない。
     * </p>
     * @param area
     * @return
     */
    public boolean isAvailableArea(String area) {
        Map<String, String> availableAreas = getCachedAvailableAreas();
        return availableAreas != null && availableAreas.containsKey(area);
    }

    @WithSpan
    public Map<String, String> getCachedAvailableAreas() {
        while (true) {
            Future<CachedAvailableAreas> f = cachedAvailableAreasContainer.get(0);
            if (f == null) {
                FutureTask<CachedAvailableAreas> ft = new FutureTask<>(() -> {
                    // Resilience4j でリトライを行う
                    Retry retry = resilienceController.getRetryRegistry().retry("fetchAvailableAreas");
                    return Try.ofSupplier(
                                    Decorators.ofSupplier(() -> fetchAvailableAreas()).withRetry(retry).decorate())
                            .recover(throwable -> null)
                            .get();
                });
                f = cachedAvailableAreasContainer.putIfAbsent(0, ft);
                if (f == null) {
                    f = ft;
                    ft.run();
                }
            }

            try {
                CachedAvailableAreas c = f.get();
                if (c.isAlive()) {
                    return c.getAvailableAreas();
                }
            } catch (InterruptedException ignore) {
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }

            cachedAvailableAreasContainer.remove(0);
        }
    }

    private CachedAvailableAreas fetchAvailableAreas() {
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(HTTP_REQUEST_CONFIG) // タイムアウトを設定する
                .build()) {
            return client.execute(new HttpGet(AREA_JSON_ENDPOINT), response -> {
                if (HttpStatus.SC_SUCCESS == response.getCode()) {
                    HttpEntity entity = response.getEntity();
                    String json = EntityUtils.toString(entity);
                    Map<String, Object> result = new ObjectMapper().readValue(json,
                            new TypeReference<Map<String, Object>>() {
                            });
                    if (result.containsKey("offices")) {
                        Map<String, String> availableAreas = new HashMap<>();
                        Map<String, Object> offices = (Map<String, Object>) result.get("offices");
                        offices.forEach((key, value) -> {
                            Map<String, Object> info = (Map<String, Object>) value;
                            availableAreas.put(key, String.valueOf(info.get("name")));
                        });
                        return new CachedAvailableAreas(availableAreas);
                    }
                }
                return null;
            });
        } catch (IOException e) {
            log.error("an error occured while fetching forecast overview", e);
            throw new RuntimeException(e);
        }
    }

}
