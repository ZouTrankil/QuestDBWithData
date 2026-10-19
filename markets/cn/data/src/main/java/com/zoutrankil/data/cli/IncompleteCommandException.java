package com.zoutrankil.data.cli;

/** Execution returned a persisted outcome that does not meet its verification gate. */
public final class IncompleteCommandException extends IllegalStateException {
    public IncompleteCommandException(String message) { super(message); }
}
