package com.pulsestudio.desktop.server;

/** Error de negocio con código HTTP y mensaje apto para mostrarse al usuario (en español). */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() { return status; }

    public static ApiException badRequest(String message) { return new ApiException(400, message); }
    public static ApiException notFound(String message) { return new ApiException(404, message); }
    public static ApiException upstream(String message) { return new ApiException(502, message); }
}
