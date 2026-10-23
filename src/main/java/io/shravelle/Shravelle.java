package io.shravelle;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shravelle APM for Java.
 *
 * <p><b>Zero code (recommended):</b> run your app with the OpenTelemetry Java agent and the
 * Shravelle settings (see {@code bin/shravelle-java-agent.sh}); Spring Boot, Tomcat, Jetty, JDBC,
 * Hibernate, Kafka, gRPC, HTTP clients and ~100 more libraries are instrumented automatically.
 *
 * <p><b>Programmatic:</b> {@code Shravelle.init(Shravelle.config().serviceName("checkout"))} at the start
 * of {@code main}. The helpers ({@link #noticeError}, {@link #setUser}, {@link #segment}, ...) work in both modes.
 */
public final class Shravelle {
    private static volatile OpenTelemetrySdk sdk;
    private static volatile Config current = config();

    private Shravelle() {}

    /** Configuration with SHRAVELLE_* environment variable defaults. */
    public static final class Config {
        String endpoint = env("SHRAVELLE_ENDPOINT", "http://localhost:4318");
        String apiKey = env("SHRAVELLE_API_KEY", env("SHRAVELLE_LICENSE_KEY", ""));
        String serviceName = env("SHRAVELLE_APP_NAME", env("SHRAVELLE_SERVICE_NAME", env("OTEL_SERVICE_NAME", "java-app")));
        String serviceVersion = env("SHRAVELLE_APP_VERSION", env("SHRAVELLE_SERVICE_VERSION", ""));
        String environment = env("SHRAVELLE_APP_ENV", env("SHRAVELLE_ENV", "production"));
        String organisation = env("SHRAVELLE_ORGANISATION", env("SHRAVELLE_ORGANIZATION", ""));
        double sampleRate = Double.parseDouble(env("SHRAVELLE_SAMPLE_RATE", "1"));
        final Map<String, String> attributes = new HashMap<>();

        public Config endpoint(String v) { endpoint = v; return this; }
        public Config apiKey(String v) { apiKey = v; return this; }
        public Config serviceName(String v) { serviceName = v; return this; }
        public Config serviceVersion(String v) { serviceVersion = v; return this; }
        public Config environment(String v) { environment = v; return this; }
        public Config organisation(String v) { organisation = v; return this; }
        public Config sampleRate(double v) { sampleRate = v; return this; }
        public Config attribute(String k, String v) { attributes.put(k, v); return this; }
    }

    public static Config config() { return new Config(); }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }

    /** Configure OpenTelemetry for Shravelle and register it globally. */
    public static synchronized OpenTelemetry init(Config c) {
        if (sdk != null) return sdk;
        current = c;
        String host = "unknown";
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { }
        StringBuilder res = new StringBuilder()
                .append("deployment.environment.name=").append(c.environment)
                .append(",host.name=").append(host)
                .append(",telemetry.distro.name=shravelle-java");
        if (!c.serviceVersion.isEmpty()) res.append(",service.version=").append(c.serviceVersion);
        if (!c.organisation.isEmpty()) res.append(",shravelle.organization=").append(c.organisation);
        c.attributes.forEach((k, v) -> res.append(',').append(k).append('=').append(v));
        Map<String, String> props = new HashMap<>();
        props.put("otel.service.name", c.serviceName);
        props.put("otel.resource.attributes", res.toString());
        props.put("otel.exporter.otlp.endpoint", c.endpoint.replaceAll("/$", ""));
        props.put("otel.exporter.otlp.protocol", "http/protobuf");
        props.put("otel.exporter.otlp.headers", "x-api-key=" + c.apiKey);
        props.put("otel.exporter.otlp.compression", "gzip");
        props.put("otel.traces.exporter", "otlp");
        props.put("otel.metrics.exporter", "otlp");
        props.put("otel.logs.exporter", "otlp");
        props.put("otel.traces.sampler", "parentbased_traceidratio");
        props.put("otel.traces.sampler.arg", String.valueOf(c.sampleRate));
        sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> props)
                .setResultAsGlobal()
                .build()
                .getOpenTelemetrySdk();
        Thread.setDefaultUncaughtExceptionHandler(chain(Thread.getDefaultUncaughtExceptionHandler()));
        Runtime.getRuntime().addShutdownHook(new Thread(Shravelle::shutdown, "shravelle-shutdown"));
        return sdk;
    }

    private static Thread.UncaughtExceptionHandler chain(Thread.UncaughtExceptionHandler prev) {
        return (t, e) -> {
            Span span = tracer().spanBuilder("uncaught exception").setAttribute("thread.name", t.getName()).startSpan();
            span.recordException(e, Attributes.of(AttributeKey.booleanKey("exception.escaped"), true));
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            span.end();
            shutdown();
            if (prev != null) prev.uncaughtException(t, e);
        };
    }

    /** Flush and close exporters (registered as a JVM shutdown hook). */
    public static synchronized void shutdown() {
        if (sdk != null) {
            sdk.getSdkTracerProvider().forceFlush();
            sdk.close();
            sdk = null;
        }
    }

    public static Tracer tracer() { return GlobalOpenTelemetry.getTracer("shravelle"); }

    /** Record an exception on the current transaction. */
    public static void noticeError(Throwable t) { noticeError(t, Map.of()); }

    public static void noticeError(Throwable t, Map<String, String> attrs) {
        Span span = Span.current();
        AttributesBuilder b = Attributes.builder();
        attrs.forEach(b::put);
        span.recordException(t, b.build());
        span.setStatus(StatusCode.ERROR, String.valueOf(t.getMessage()));
    }

    public static void setUser(String id) { Span.current().setAttribute("enduser.id", id); }

    public static void addAttribute(String key, String value) { Span.current().setAttribute(key, value); }

    public static void addAttribute(String key, long value) { Span.current().setAttribute(key, value); }

    /** Business event on the current span, e.g. recordEvent("order.placed", Map.of("amount", "499")). */
    public static void recordEvent(String name, Map<String, String> attrs) {
        AttributesBuilder b = Attributes.builder();
        attrs.forEach(b::put);
        Span.current().addEvent(name, b.build());
    }

    /** Run code inside a custom span. */
    public static <T> T segment(String name, Supplier<T> body) {
        Span span = tracer().spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Custom counter metric. */
    public static LongCounter counter(String name) { return GlobalOpenTelemetry.getMeter("shravelle").counterBuilder(name).build(); }

    /** Send a deployment marker. */
    public static void recordDeployment(String version, String commit, String description) throws Exception {
        Config c = current;
        String body = String.format("{\"service\":\"%s\",\"version\":\"%s\",\"commit\":\"%s\",\"description\":\"%s\",\"user\":\"%s\"}",
                json(c.serviceName), json(version), json(commit), json(description), json(env("USER", "")));
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.endpoint.replaceAll("/$", "") + "/v1/deployments"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("X-API-Key", c.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new IllegalStateException("deployment marker failed: HTTP " + resp.statusCode());
    }

    private static String json(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
