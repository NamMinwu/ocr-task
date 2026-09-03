package com.dreamtrue.ocr.domain;

public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Failed {

    record Ok<T>(T value) implements Outcome<T> {}

    record Failed<T>(String reason) implements Outcome<T> {}

    static <T> Outcome<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Outcome<T> failed(String reason) {
        return new Failed<>(reason);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }
}
