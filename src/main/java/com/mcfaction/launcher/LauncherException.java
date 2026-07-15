package com.mcfaction.launcher;

/** Anything that stops the launcher from proceeding - shown to the user as a plain error dialog. */
public class LauncherException extends RuntimeException {

    public LauncherException(String message) {
        super(message);
    }

    public LauncherException(String message, Throwable cause) {
        super(message, cause);
    }
}
