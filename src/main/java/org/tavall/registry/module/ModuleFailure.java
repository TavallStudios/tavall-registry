package org.tavall.registry.module;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

/**
 * Immutable diagnostic representation of a lifecycle transition failure.
 * <p>
 * <b>Architectural Role:</b> Replaces generic exceptions with structured, typed failure tokens
 * suitable for logging, remote propagation, and automated recovery decisions.
 *
 * @param code    machine-readable failure category code (e.g. {@code "reload_not_supported"},
 *                {@code "stale_config_generation"}, {@code "unauthorized"})
 * @param message human-readable summary of the failure reason
 * @param details optional detailed stack trace, diagnostics, or context; empty string if none
 */
public record ModuleFailure(String code, String message, String details) {

    /**
     * Constructs and validates a {@link ModuleFailure}.
     *
     * @param code    machine-readable code; must not be blank
     * @param message summary description; must not be blank
     * @param details detailed diagnostic string; non-null
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if {@code code} or {@code message} is blank
     */
    public ModuleFailure {
        Objects.requireNonNull(code, "ModuleFailure code must not be null");
        Objects.requireNonNull(message, "ModuleFailure message must not be null");
        Objects.requireNonNull(details, "ModuleFailure details must not be null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("ModuleFailure code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("ModuleFailure message must not be blank");
        }
    }

    /**
     * Factory creating a failure with code and message, with empty details.
     *
     * @param code    machine-readable code
     * @param message summary message
     * @return a new {@link ModuleFailure} instance
     */
    public static ModuleFailure of(String code, String message) {
        return new ModuleFailure(code, message, "");
    }

    /**
     * Factory converting a throwable into a structured {@link ModuleFailure}.
     *
     * @param code      machine-readable code
     * @param throwable cause throwable; must not be null
     * @return a new {@link ModuleFailure} instance capturing the exception and trace
     */
    public static ModuleFailure from(String code, Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return new ModuleFailure(code, message, writer.toString());
    }
}
