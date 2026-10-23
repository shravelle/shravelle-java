# Java (and Kotlin/Scala on the JVM)

## Zero code (recommended)
```bash
SHRAVELLE_API_KEY=shv_ing_xxx SHRAVELLE_SERVICE_NAME=orders \
  sdks/shravelle-sdk-java/bin/shravelle-java-agent.sh java -jar app.jar
```
The script downloads the OpenTelemetry Java agent once (pin `OTEL_AGENT_VERSION`), sets the OTLP settings
and runs your command with `-javaagent`. Instrumented automatically: Spring Boot/MVC/WebFlux, Servlet,
Tomcat, Jetty, Undertow, Quarkus, Micronaut, JDBC, Hibernate, R2DBC, Kafka, RabbitMQ, gRPC, OkHttp,
Apache HttpClient, Redis (Jedis/Lettuce), MongoDB, Elasticsearch, Logback/Log4j2 (logs with trace ids), and
around 100 more. In Docker, add the jar to the image and set `JAVA_TOOL_OPTIONS=-javaagent:/otel/agent.jar` plus the
`OTEL_*` variables from [opentelemetry.md](https://shravelle.com/docs.html#otel).

## Helpers (`io.shravelle:shravelle`, `sdks/shravelle-sdk-java/pom.xml`)
```java
Shravelle.init(Shravelle.config().serviceName("orders"));   // only without the agent
Shravelle.noticeError(e, Map.of("order_id", "123"));
Shravelle.setUser(userId);
Shravelle.addAttribute("cart_value", 1450L);
Shravelle.recordEvent("coupon_applied", Map.of("code", "DIWALI"));
Shravelle.counter("orders_total").add(1);
Shravelle.recordDeployment("1.4.2", sha, "release");
```
They use `GlobalOpenTelemetry`, so they work with the agent too.

Status: not compiled in this build environment. Run `mvn package` in your CI.


---
Part of [Shravelle](https://shravelle.com). Setup guide for every platform: [shravelle.com/docs](https://shravelle.com/docs.html) · Get your keys: [shravelle.com/get-started](https://shravelle.com/get-started.html)
