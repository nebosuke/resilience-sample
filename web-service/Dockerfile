FROM openjdk:17-jdk-slim
LABEL stage=builder
## resilience/web-service の内容を /web-src にコピー
COPY . /web-src
WORKDIR /web-src

## maven で jar パッケージをビルド
RUN ./mvnw package

FROM openjdk:17-jdk-slim

## ランタイムなのでパッケージは最新に更新しておく
RUN set -x && \
    apt-get update && \
    apt-get -y upgrade && \
    apt-get -y install curl

## ビルド結果を / にコピー
COPY --from=0 /web-src/target/web-0.0.1-SNAPSHOT.jar /

## OpenTelemetry の javaagent をダウンロード
RUN curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.18.0/opentelemetry-javaagent.jar > /opentelemetry-javaagent-1.18.0.jar

## javaagent を指定してサーバーを起動
CMD java -javaagent:/opentelemetry-javaagent-1.18.0.jar \
    -Dotel.metrics.exporter=none \
    -Dotel.logs.exporter=none \
    -Dotel.resource.attributes=service.name=web \
    -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \
    -jar /web-0.0.1-SNAPSHOT.jar 
