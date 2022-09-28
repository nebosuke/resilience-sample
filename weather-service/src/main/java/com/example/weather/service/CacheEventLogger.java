package com.example.weather.service;

import lombok.extern.slf4j.Slf4j;
import org.ehcache.event.CacheEvent;
import org.ehcache.event.CacheEventListener;

@Slf4j
public class CacheEventLogger implements CacheEventListener<String, WeatherDataService.ForecastOverview> {

    @Override
    public void onEvent(CacheEvent<? extends String, ? extends WeatherDataService.ForecastOverview> cacheEvent) {
        if (log.isTraceEnabled()) {
            log.trace("key={}, old={}, new={}", cacheEvent.getKey(), cacheEvent.getOldValue(),
                    cacheEvent.getNewValue());
        }
    }
}
