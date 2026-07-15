package com.verityai.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verityai.plugin.VerityAI;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Talks to the OpenRouter chat-completions endpoint. Supports:
 *  - streaming (SSE) with a per-chunk callback
 *  - multiple API keys (tries the next one on auth/rate-limit failures)
 *  - a fallback model list (tries the next model if the primary fails)
 *  - automatic retries with backoff for transient errors (429 / 5xx) on the
 *    SAME key/model before moving on, since those are often momentary
 *  - configurable connect/request timeouts (ai.connect-timeout-seconds,
 *    ai.request-timeout-seconds)
 */
public class OpenRouterClient {

    private final VerityAI plugin;
    private volatile HttpClient httpClient;
    private volatile int httpClientBuiltForTimeout = -1;

    public OpenRouterClient(VerityAI plugin) {
        this.plugin = plugin;
    }

    public record AiResult(String content, String modelUsed, int totalTokens) {}

    public record ToolCall(String id, String name, String argumentsJson) {}

    /** Raw result of a single request: content may be null when the model instead requested tool calls. */
    private record RawResult(String content, List<ToolCall> toolCalls, int totalTokens) {}

    /** Implemented by the caller to actually run a function and return its (JSON-ish) result text. */
    public interface FunctionExecutor {
        String execute(String functionName, String argumentsJson) throws Exception;
    }

    /** Thrown internally to distinguish "worth retrying the same key" from a hard failure. */
    private static class TransientFailure extends RuntimeException {
        final int statusCode;
        TransientFailure(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /**
     * Performs a (optionally streaming) chat completion, retrying transient
     * failures on the same key/model a few times, then trying each remaining
     * configured API key / model combination in order until one succeeds.
     *
     * @param onChunk called with each incremental text chunk when streaming is enabled; may be null.
     */
    public AiResult complete(JsonArray messages, boolean stream, Consumer<String> onChunk) throws Exception {
        return complete(messages, stream, null, onChunk);
    }

    /** Same as {@link #complete(JsonArray, boolean, Consumer)}, but lets the caller force a specific primary model. */
    public AiResult complete(JsonArray messages, boolean stream, String modelOverride, Consumer<String> onChunk) throws Exception {
        var cfg = plugin.getConfigManager();

        List<String> keys = cfg.getApiKeys();
        List<String> models = modelList(modelOverride);

        Exception lastError = null;
        int maxRetries = cfg.getMaxRetriesPerKey();

        for (String model : models) {
            for (String key : keys) {
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        if (stream) {
                            return doStreamingRequest(messages, model, key, cfg.getApiUrl(), cfg.getMaxTokens(), cfg.getTemperature(), onChunk);
                        }
                        RawResult raw = doBlockingRequest(messages, model, key, cfg.getApiUrl(), cfg.getMaxTokens(), cfg.getTemperature(), null);
                        return new AiResult(raw.content() == null ? "" : raw.content(), model, raw.totalTokens());
                    } catch (TransientFailure tf) {
                        lastError = tf;
                        plugin.getDebugLogger().debug(String.format(
                                "VerityAI: transient failure (HTTP %d) for model=%s, attempt %d/%d — %s",
                                tf.statusCode, model, attempt + 1, maxRetries + 1, tf.getMessage()));
                        if (attempt < maxRetries) {
                            sleepBackoff(attempt);
                            continue;
                        }
                        // Retries exhausted for this key — fall through to the next key/model.
                    } catch (Exception e) {
                        lastError = e;
                        plugin.getDebugLogger().debug("VerityAI: request failed for model=" + model
                                + " (" + e.getMessage() + "), trying next option.");
                    }
                    break; // move to next key
                }
            }
        }

        if (lastError != null) {
            plugin.getDebugLogger().errorLog("OpenRouterClient.complete (all keys/models exhausted)", lastError);
        }
        throw lastError != null ? lastError : new IllegalStateException("No API keys configured.");
    }

    /**
     * Function-calling loop: sends messages + tool schemas, and whenever the model
     * asks for a tool call, runs it via {@code executor} and feeds the result back,
     * repeating up to {@code maxRounds} times before returning the final text answer.
     * Always non-streaming (tool-calling + SSE streaming together isn't supported here).
     */
    public AiResult completeWithFunctions(JsonArray messages, String modelOverride, JsonArray tools,
                                           FunctionExecutor executor, int maxRounds) throws Exception {
        var cfg = plugin.getConfigManager();
        List<String> keys = cfg.getApiKeys();
        List<String> models = modelList(modelOverride);

        Exception lastError = null;
        String usedModel = models.isEmpty() ? cfg.getModel() : models.get(0);
        int totalTokens = 0;

        for (String model : models) {
            for (String key : keys) {
                try {
                    usedModel = model;
                    JsonArray convo = deepCopy(messages);
                    for (int round = 0; round < Math.max(1, maxRounds); round++) {
                        RawResult raw = doBlockingRequest(convo, model, key, cfg.getApiUrl(), cfg.getMaxTokens(), cfg.getTemperature(), tools);
                        totalTokens += raw.totalTokens();

                        if (raw.toolCalls() == null || raw.toolCalls().isEmpty()) {
                            return new AiResult(raw.content() == null ? "" : raw.content(), model, totalTokens);
                        }

                        // Record the assistant's tool-call request, then run each tool and append its result.
                        JsonObject assistantMsg = new JsonObject();
                        assistantMsg.addProperty("role", "assistant");
                        assistantMsg.addProperty("content", raw.content() == null ? "" : raw.content());
                        JsonArray toolCallsJson = new JsonArray();
                        for (ToolCall call : raw.toolCalls()) {
                            JsonObject tc = new JsonObject();
                            tc.addProperty("id", call.id());
                            tc.addProperty("type", "function");
                            JsonObject fn = new JsonObject();
                            fn.addProperty("name", call.name());
                            fn.addProperty("arguments", call.argumentsJson());
                            tc.add("function", fn);
                            toolCallsJson.add(tc);
                        }
                        assistantMsg.add("tool_calls", toolCallsJson);
                        convo.add(assistantMsg);

                        for (ToolCall call : raw.toolCalls()) {
                            String result;
                            try {
                                result = executor.execute(call.name(), call.argumentsJson());
                            } catch (Exception ex) {
                                result = "Error running function: " + ex.getMessage();
                            }
                            JsonObject toolMsg = new JsonObject();
                            toolMsg.addProperty("role", "tool");
                            toolMsg.addProperty("tool_call_id", call.id());
                            toolMsg.addProperty("content", result);
                            convo.add(toolMsg);
                        }
                        // loop continues: send the tool results back to the model for another round
                    }
                    // Ran out of rounds — ask once more without tools to force a final answer.
                    RawResult finalRaw = doBlockingRequest(convo, model, key, cfg.getApiUrl(), cfg.getMaxTokens(), cfg.getTemperature(), null);
                    return new AiResult(finalRaw.content() == null ? "" : finalRaw.content(), model, totalTokens + finalRaw.totalTokens());
                } catch (Exception e) {
                    lastError = e;
                    plugin.getDebugLogger().debug("VerityAI: function-calling request failed for model=" + model
                            + " (" + e.getMessage() + "), trying next option.");
                }
            }
        }

        if (lastError != null) {
            plugin.getDebugLogger().errorLog("OpenRouterClient.completeWithFunctions (all keys/models exhausted)", lastError);
        }
        throw lastError != null ? lastError : new IllegalStateException("No API keys configured.");
    }

    /** Calls the configured embeddings endpoint and returns the embedding vector for a piece of text. */
    public double[] embed(String text) throws Exception {
        var cfg = plugin.getConfigManager();
        List<String> keys = cfg.getApiKeys();
        if (keys.isEmpty()) {
            throw new IllegalStateException("No API keys configured.");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.getEmbeddingsModel());
        body.addProperty("input", text);

        Exception lastError = null;
        for (String key : keys) {
            try {
                HttpRequest request = baseRequest(cfg.getEmbeddingsApiUrl(), key)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throwForStatus(response.statusCode(), response.body());
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray data = json.getAsJsonArray("data");
                JsonArray vector = data.get(0).getAsJsonObject().getAsJsonArray("embedding");
                double[] result = new double[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    result[i] = vector.get(i).getAsDouble();
                }
                return result;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("Embedding request failed.");
    }

    private List<String> modelList(String modelOverride) {
        var cfg = plugin.getConfigManager();
        List<String> models = new java.util.ArrayList<>();
        models.add((modelOverride != null && !modelOverride.isBlank()) ? modelOverride : cfg.getModel());
        models.addAll(cfg.getFallbackModels());
        return models;
    }

    private JsonArray deepCopy(JsonArray array) {
        return JsonParser.parseString(array.toString()).getAsJsonArray();
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(4000L, 300L * (1L << attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Rebuilds the HttpClient only when the configured connect timeout actually changes. */
    private synchronized HttpClient httpClient() {
        int timeoutSeconds = Math.max(1, plugin.getConfigManager().getConnectTimeoutSeconds());
        if (httpClient == null || httpClientBuiltForTimeout != timeoutSeconds) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            httpClientBuiltForTimeout = timeoutSeconds;
        }
        return httpClient;
    }

    private HttpRequest.Builder baseRequest(String url, String apiKey) {
        var cfg = plugin.getConfigManager();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(Math.max(1, cfg.getRequestTimeoutSeconds())));

        if (cfg.getSiteUrl() != null && !cfg.getSiteUrl().isBlank()) {
            builder.header("HTTP-Referer", cfg.getSiteUrl());
        }
        if (cfg.getSiteName() != null && !cfg.getSiteName().isBlank()) {
            builder.header("X-Title", cfg.getSiteName());
        }
        return builder;
    }

    private JsonObject buildBody(JsonArray messages, String model, int maxTokens, double temperature, boolean stream, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("stream", stream);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        return body;
    }

    /** 429 (rate limited) and 5xx (server-side) are treated as transient and worth retrying. */
    private boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void throwForStatus(int statusCode, String body) {
        String message = "HTTP " + statusCode + ": " + truncate(body);
        if (isTransient(statusCode)) {
            throw new TransientFailure(statusCode, message);
        }
        throw new RuntimeException(message);
    }

    private RawResult doBlockingRequest(JsonArray messages, String model, String apiKey, String url,
                                         int maxTokens, double temperature, JsonArray tools) throws Exception {
        JsonObject body = buildBody(messages, model, maxTokens, temperature, false, tools);

        HttpRequest request = baseRequest(url, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throwForStatus(response.statusCode(), response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Empty choices in response.");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");

        String content = (message.has("content") && !message.get("content").isJsonNull())
                ? message.get("content").getAsString().trim() : null;

        List<ToolCall> toolCalls = new java.util.ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (var element : message.getAsJsonArray("tool_calls")) {
                JsonObject tc = element.getAsJsonObject();
                String id = tc.has("id") ? tc.get("id").getAsString() : "";
                JsonObject fn = tc.getAsJsonObject("function");
                String name = fn.get("name").getAsString();
                String args = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        int totalTokens = 0;
        if (json.has("usage") && json.getAsJsonObject("usage").has("total_tokens")) {
            totalTokens = json.getAsJsonObject("usage").get("total_tokens").getAsInt();
        }

        return new RawResult(content, toolCalls, totalTokens);
    }

    private AiResult doStreamingRequest(JsonArray messages, String model, String apiKey, String url,
                                         int maxTokens, double temperature, Consumer<String> onChunk) throws Exception {
        JsonObject body = buildBody(messages, model, maxTokens, temperature, true, null);

        HttpRequest request = baseRequest(url, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response =
                httpClient().send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            String errBody = response.body().reduce("", (a, b) -> a + b);
            throwForStatus(response.statusCode(), errBody);
        }

        StringBuilder full = new StringBuilder();
        int[] totalTokens = {0};

        response.body().forEach(line -> {
            if (line == null || line.isBlank() || !line.startsWith("data:")) {
                return;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.equals("[DONE]")) {
                return;
            }
            try {
                JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
                JsonArray choices = chunk.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                        String piece = delta.get("content").getAsString();
                        full.append(piece);
                        if (onChunk != null) {
                            onChunk.accept(piece);
                        }
                    }
                }
                if (chunk.has("usage") && chunk.getAsJsonObject("usage").has("total_tokens")) {
                    totalTokens[0] = chunk.getAsJsonObject("usage").get("total_tokens").getAsInt();
                }
            } catch (Exception ignored) {
                // skip malformed SSE fragment
            }
        });

        if (full.isEmpty()) {
            throw new RuntimeException("Streaming response produced no content.");
        }

        return new AiResult(full.toString().trim(), model, totalTokens[0]);
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
