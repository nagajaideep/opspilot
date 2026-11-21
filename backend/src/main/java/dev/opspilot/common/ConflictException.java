package dev.opspilot.common;

/** Thrown when an action is not valid for the current state, e.g. approving a DRAFT. Maps to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
