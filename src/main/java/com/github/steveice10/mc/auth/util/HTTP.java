package com.github.steveice10.mc.auth.util;

import com.github.steveice10.mc.auth.exception.request.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jdk.nashorn.internal.objects.NativeArray;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Utilities for making HTTP requests.
 */
public class HTTP {
    private static final Gson GSON;

    static {
        GSON = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDSerializer()).create();
    }

    private HTTP() {
    }

    /**
     * Makes an HTTP request.
     *
     * @param proxy Proxy to use when making the request.
     * @param uri   URI to make the request to.
     * @param input Input to provide in the request.
     * @throws IllegalArgumentException If the given proxy or URI is null.
     * @throws RequestException If an error occurs while making the request.
     */
    public static void makeRequest(Proxy proxy, URI uri, Object input) throws RequestException {
        makeRequest(proxy, uri, input, null);
    }

    /**
     * Makes an HTTP request.
     *
     * @param proxy        Proxy to use when making the request.
     * @param uri          URI to make the request to.
     * @param input        Input to provide in the request.
     * @param responseType Class to provide the response as.
     * @param <T>          Type to provide the response as.
     * @return The response of the request.
     * @throws IllegalArgumentException If the given proxy or URI is null.
     * @throws RequestException If an error occurs while making the request.
     */
    public static <T> T makeRequest(Proxy proxy, URI uri, Object input, Class<T> responseType) throws RequestException {
        if(proxy == null) {
            throw new IllegalArgumentException("Proxy cannot be null.");
        } else if(uri == null) {
            throw new IllegalArgumentException("URI cannot be null.");
        }

        JsonElement response;
        try {
            response = input == null ? performGetRequest(proxy, uri) : performPostRequest(proxy, uri, GSON.toJson(input), "application/json");
        } catch(IOException e) {
            throw new ServiceUnavailableException("Could not make request to '" + uri + "'.", e);
        }

        if(response != null) {
            checkForError(response);

            if(responseType != null) {
                return GSON.fromJson(response, responseType);
            }
        }

        return null;
    }

    /**
     * Makes an HTTP request as a from.
     *
     * @param proxy        Proxy to use when making the request.
     * @param uri          URI to make the request to.
     * @param input        Input to provide in the request.
     * @param responseType Class to provide the response as.
     * @param <T>          Type to provide the response as.
     * @return The response of the request.
     * @throws IllegalArgumentException If the given proxy or URI is null.
     * @throws RequestException If an error occurs while making the request.
     */
    public static <T> T makeRequestForm(Proxy proxy, URI uri, Map<String, String> input, Class<T> responseType) throws RequestException {
        if(proxy == null) {
            throw new IllegalArgumentException("Proxy cannot be null.");
        } else if(uri == null) {
            throw new IllegalArgumentException("URI cannot be null.");
        }

        StringBuilder inputString = new StringBuilder();
        for (Map.Entry<String, String> inputField : input.entrySet()) {
            if (inputString.length() > 0) {
                inputString.append("&");
            }

            try {
                inputString.append(URLEncoder.encode(inputField.getKey(), StandardCharsets.UTF_8.toString()));
                inputString.append("=");
                inputString.append(URLEncoder.encode(inputField.getValue(), StandardCharsets.UTF_8.toString()));
            } catch (UnsupportedEncodingException ignored) { }
        }

        JsonElement response;
        try {
            response = performPostRequest(proxy, uri, inputString.toString(), "application/x-www-form-urlencoded");
        } catch(IOException e) {
            throw new ServiceUnavailableException("Could not make request to '" + uri + "'.", e);
        }

        if(response != null) {
            checkForError(response);

            if(responseType != null) {
                return GSON.fromJson(response, responseType);
            }
        }

        return null;
    }

    private static void checkForError(JsonElement response) throws RequestException {
        if(response.isJsonObject()) {
            JsonObject object = response.getAsJsonObject();
            if(object.has("error")) {
                String error = object.get("error").getAsString();
                String cause = object.has("cause") ? object.get("cause").getAsString() : "";
                String errorMessage = object.has("errorMessage") ? object.get("errorMessage").getAsString() : "";
                errorMessage = object.has("error_description") ? object.get("error_description").getAsString() : errorMessage;
                if(!error.equals("")) {
                    if(error.equals("ForbiddenOperationException")) {
                        if (cause != null && cause.equals("UserMigratedException")) {
                            throw new UserMigratedException(errorMessage);
                        } else {
                            throw new InvalidCredentialsException(errorMessage);
                        }
                    } else if (error.equals("authorization_pending")) {
                        throw new AuthPendingException(errorMessage);
                    } else {
                        throw new RequestException(errorMessage);
                    }
                }
            }
        }
    }

    private static JsonElement performGetRequest(Proxy proxy, URI uri) throws IOException {
        HttpURLConnection connection = createUrlConnection(proxy, uri);
        connection.setDoInput(true);

        return processResponse(connection);
    }

    private static JsonElement performPostRequest(Proxy proxy, URI uri, String post, String type) throws IOException {
        byte[] bytes = post.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = createUrlConnection(proxy, uri);
        connection.setRequestProperty("Content-Type", type + "; charset=utf-8");
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        connection.setDoInput(true);
        connection.setDoOutput(true);

        try(OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }

        return processResponse(connection);
    }

    private static HttpURLConnection createUrlConnection(Proxy proxy, URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(proxy);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        return connection;
    }

    private static JsonElement processResponse(HttpURLConnection connection) throws IOException {
        try(InputStream in = connection.getResponseCode() == 200 ? connection.getInputStream() : connection.getErrorStream()) {
            return in != null ? GSON.fromJson(new InputStreamReader(in), JsonElement.class) : null;
        }
    }
}
