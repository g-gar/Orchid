package com.ggar.orchid.executor.actions;

import com.ggar.orchid.executor.ActionExecutor;
import com.ggar.orchid.model.Action;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    @SuppressWarnings("unchecked")
    public void execute(Action action) {
        Map<String, Object> inputs = action.getInputs();
        if (inputs == null) {
            action.setStatus("FAILED");
            action.setError("Inputs map is null");
            return;
        }

        String urlString = (String) inputs.get("url");
        String method = (String) inputs.get("method");
        Map<String, String> headersMap = (Map<String, String>) inputs.get("headers");
        String body = (String) inputs.get("body");
        String mapToType = (String) inputs.get("mapToType"); // Optional

        if (urlString == null || urlString.isEmpty()) {
            action.setStatus("FAILED");
            action.setError("URL is required");
            return;
        }

        if (method == null || method.isEmpty()) {
            action.setStatus("FAILED");
            action.setError("HTTP method is required");
            return;
        }

        try {
            Map<String, Object> result = executeRestRequest(urlString, method, headersMap, body, mapToType);
            action.getOutputs().put("statusCode", result.get("statusCode"));
            action.getOutputs().put("responseBody", result.get("responseBody"));
            action.setStatus("COMPLETED");
        } catch (URISyntaxException e) {
            action.setStatus("FAILED");
            action.setError("Invalid URL syntax: " + e.getMessage());
        } catch (IOException e) {
            action.setStatus("FAILED");
            action.setError("I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            action.setStatus("FAILED");
            action.setError("Request interrupted: " + e.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupted status
        } catch (ClassNotFoundException e) {
            action.setStatus("FAILED");
            action.setError("Class not found for deserialization: " + e.getMessage());
        } catch (Exception e) { // Catch any other unexpected exceptions
            action.setStatus("FAILED");
            action.setError("An unexpected error occurred: " + e.getMessage());
            // It's good practice to log the stack trace for unexpected errors
            // Logger.getLogger(HttpRestActionExecutor.class.getName()).log(Level.SEVERE, "Unexpected error in execute", e);
        }
    }

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
