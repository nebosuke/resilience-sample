FROM openjdk:17-jdk-slim
LABEL stage=builder

## resilience/weather-service の内容を /weather-src にコピー
COPY . /weather-src
WORKDIR /weather-src

## maven で jar パッケージをビルド
RUN ./mvnw package

FROM openjdk:17-jdk-slim

## ランタイムなのでパッケージは最新に更新しておく
RUN set -x && \
    apt-get update && \
    apt-get -y upgrade

## ビルド結果を / にコピー
COPY --from=0 /weather-src/target/weather-0.0.1-SNAPSHOT.jar /

## OpenTelemetry の javaagent を / にコピー
COPY otel/opentelemetry-javaagent-1.18.0.jar /

## javaagent を指定してサーバーを起動
CMD java -javaagent:/opentelemetry-javaagent-1.18.0.jar \
    -Dotel.metrics.exporter=none \
    -Dotel.logs.exporter=none \
    -Dotel.resource.attributes=service.name=weather \
    -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \
    -jar /weather-0.0.1-SNAPSHOT.jar 
