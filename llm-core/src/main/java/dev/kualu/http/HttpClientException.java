package dev.kualu.http;

/** Thrown when an HTTP request cannot be completed. */
public class HttpClientException extends RuntimeException {

    public HttpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
