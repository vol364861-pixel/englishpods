package com.englishpods.generation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounded requests; no supplier response body, credential or URL reaches errors/logs. */
final class GenerationHttpClient {
    private static final int MAX_RESPONSE = 32 * 1024 * 1024;
    private final HttpClient client;
    private final int maxResponse;
    GenerationHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build(), MAX_RESPONSE);
    }
    GenerationHttpClient(HttpClient client, int maxResponse) {
        this.client = client; this.maxResponse = maxResponse;
    }

    byte[] post(String endpoint, String key, String body, boolean sse, String provider) {
        var builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sse) builder.header("X-DashScope-SSE", "enable").header("Accept", "text/event-stream");
        return send(builder.build(), provider);
    }

    byte[] download(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (RuntimeException e) { throw new GenerationException("Qwen 返回的音频地址无效"); }
        // Official examples return legacy HTTP OSS links. Upgrade only the known OSS service domain.
        if ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getHost().matches("[a-zA-Z0-9.-]+\\.oss-[a-zA-Z0-9-]+\\.aliyuncs\\.com")) {
            url = "https" + url.substring(4);
            uri = URI.create(url);
        }
        if (!GenerationSettings.endpointValid(url)) throw new GenerationException("Qwen 返回了不安全的音频地址");
        // Deliberately uses a fresh GET request without the API Authorization header.
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90)).GET().build(), "音频下载");
    }

    private byte[] send(HttpRequest request, String provider) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(request, ignored -> new LimitedBody(maxResponse));
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response.body();
                if ((status == 429 || status >= 500) && attempt < 2) { backoff(attempt); continue; }
                throw new GenerationException(provider + " 请求失败（HTTP " + status + "），请检查账号、模型和地域配置后重试");
            } catch (IOException e) {
                String failure = failureCategory(e);
                if (failure.equals("响应超过大小限制")) throw new GenerationException(provider + " " + failure + "（上限 " + maxResponse + " 字节）");
                if (attempt == 2) throw new GenerationException(provider + " " + failure + "，请稍后重试");
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GenerationException("生成任务已中断，可重新生成");
            }
        }
        throw new GenerationException(provider + " 请求失败");
    }

    private void backoff(int attempt) {
        try { Thread.sleep(250L * (attempt + 1)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new GenerationException("生成任务已中断，可重新生成"); }
    }

    static String failureCategory(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseLimitException) return "响应超过大小限制";
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) return "网络请求超时";
            if (cause instanceof javax.net.ssl.SSLException) return "TLS 安全连接失败";
            if (cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException) return "DNS 域名解析失败";
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof java.net.ConnectException) return "网络连接失败";
        return "网络传输失败";
    }

    private static final class ResponseLimitException extends IOException {}

    private static final class LimitedBody implements BodySubscriber<byte[]> {
        private final int limit;
        LimitedBody(int limit) { this.limit = limit; }
        private final BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long count;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) count += buffer.remaining();
            if (count > limit) { subscription.cancel(); delegate.onError(new ResponseLimitException()); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
