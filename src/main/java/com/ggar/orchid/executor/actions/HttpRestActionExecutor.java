package com.ggar.orchid.executor.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ggar.orchid.executor.ActionExecutor;
import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.RestRequestAction; // Import RestRequestAction

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class HttpRestActionExecutor implements ActionExecutor {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpRestActionExecutor() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void execute(Action action) {
        if (!(action instanceof RestRequestAction)) {
            action.setStatus("FAILED");
            action.setError("Invalid action type. Expected RestRequestAction, got " + action.getClass().getName());
            return;
        }
        RestRequestAction restAction = (RestRequestAction) action;

        String urlString = restAction.getUrl();
        String method = restAction.getMethod();
        Map<String, String> headersMap = restAction.getHeaders();
        String body = restAction.getBody();
        String mapToType = restAction.getMapToType(); // Optional

        if (urlString == null || urlString.isEmpty()) {
            restAction.setStatus("FAILED");
            restAction.setError("URL is required");
            return;
        }

        if (method == null || method.isEmpty()) {
            restAction.setStatus("FAILED");
            restAction.setError("HTTP method is required");
            return;
        }

        try {
            Map<String, Object> result = executeRestRequest(urlString, method, headersMap, body, mapToType);
            restAction.getOutputs().put("statusCode", result.get("statusCode"));
            restAction.getOutputs().put("responseBody", result.get("responseBody"));
            restAction.setStatus("COMPLETED");
        } catch (URISyntaxException e) {
            restAction.setStatus("FAILED");
            restAction.setError("Invalid URL syntax: " + e.getMessage());
        } catch (IOException e) {
            restAction.setStatus("FAILED");
            restAction.setError("I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            restAction.setStatus("FAILED");
            restAction.setError("Request interrupted: " + e.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupted status
        } catch (ClassNotFoundException e) {
            restAction.setStatus("FAILED");
            restAction.setError("Class not found for deserialization: " + e.getMessage());
        } catch (Exception e) { // Catch any other unexpected exceptions
            restAction.setStatus("FAILED");
            restAction.setError("An unexpected error occurred: " + e.getMessage());
            // It's good practice to log the stack trace for unexpected errors
            // Logger.getLogger(HttpRestActionExecutor.class.getName()).log(Level.SEVERE, "Unexpected error in execute", e);
        }
    }

    // Making this method public as per original, but consider if it needs to be.
    // If it's only used by execute(), it could be private.
    public Map<String, Object> executeRestRequest(String urlString, String method, Map<String, String> headersMap, String body, String mapToType)
            throws URISyntaxException, IOException, InterruptedException, ClassNotFoundException {
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(urlString));

        if (headersMap != null) {
            headersMap.forEach(requestBuilder::header);
        }

        HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isEmpty())
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

        switch (method.toUpperCase()) {
            case "POST":
                requestBuilder.POST(bodyPublisher);
                break;
            case "PUT":
                requestBuilder.PUT(bodyPublisher);
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            case "GET":
            default:
                requestBuilder.GET();
                break;
        }

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Object responseBody = response.body();
        if (mapToType != null && !mapToType.isEmpty()) {
            try {
                Class<?> clazz = Class.forName(mapToType);
                responseBody = objectMapper.readValue(response.body(), clazz);
            } catch (ClassNotFoundException | IOException e) {
                // Log or handle deserialization failure, fall back to String
                System.err.println("Failed to deserialize response to " + mapToType + ": " + e.getMessage());
                // responseBody remains as String
            }
        }

        return Map.of(
                "statusCode", response.statusCode(),
                "responseBody", responseBody
        );
    }
}
