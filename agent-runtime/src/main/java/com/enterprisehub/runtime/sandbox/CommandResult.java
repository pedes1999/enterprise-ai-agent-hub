package com.enterprisehub.runtime.sandbox;

import java.time.Duration;

public record CommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean outputTruncated,
        Duration duration) {

    public boolean succeeded() {
        return exitCode == 0;
    }
}
