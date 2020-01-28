package com.r3.testing.retry;

import org.gradle.api.logging.Logger;

import java.util.concurrent.Callable;

public final class Retry {

    public interface RetryStrategy {
        <T> T run(Callable<T> op) throws RetryException;
    }

    public static final class RetryException extends RuntimeException {
        public RetryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static RetryStrategy fixed(int times, Logger logger) {
        if (times < 1) throw new IllegalArgumentException();
        return new RetryStrategy() {
            @Override
            public <T> T run(Callable<T> op) {
                int run = 0;
                Exception last = null;
                while (run < times) {
                    try {
                        return op.call();
                    } catch (Exception e) {
                        last = e;
                        logger.error("Exception during retryable operation. Will try another " + (times-(run+1)) + " times ", e);
                    }
                    run++;
                }
                throw new RetryException("Operation failed " + run + " times", last);
            }
        };
    }
}



