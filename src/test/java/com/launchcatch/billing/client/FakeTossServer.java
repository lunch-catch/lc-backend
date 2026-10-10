package com.launchcatch.billing.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * 토스 응답을 흉내 내는 로컬 HTTP 서버다. 실제 소켓으로 붙으므로 연결 거부와 읽기 타임아웃 같은
 * 전송 단계 실패도 진짜 예외로 재현된다. 모킹 라이브러리를 새로 들이지 않으려고 JDK 서버를 쓴다.
 *
 * 응답은 한 번에 하나만 정해 둔다. 요청마다 달라야 하는 시험은 요청 사이에 reply 를 다시 부른다.
 */
final class FakeTossServer implements AutoCloseable {

    record Recorded(String method, String path, String authorization, String idempotencyKey, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMillis;

    private FakeTossServer(HttpServer server) {
        this.server = server;
    }

    static FakeTossServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeTossServer fake = new FakeTossServer(server);
            server.createContext("/", fake::handle);
            server.setExecutor(fake.executor);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    void reply(int status, String body) {
        replyAfter(0, status, body);
    }

    void replyAfter(long delayMillis, int status, String body) {
        this.delayMillis = delayMillis;
        this.status = status;
        this.body = body;
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                requestBody));
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 연결이다
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
