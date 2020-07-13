package com.r3.sample;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class Sample1Test {
    @Test
    void firstTest() {
        assertThat(System.nanoTime()).isGreaterThan(0);
    }

    @Test
    void secondTest() {
        assertThat(System.getProperty("user.dir")).isNotNull();
    }
}
