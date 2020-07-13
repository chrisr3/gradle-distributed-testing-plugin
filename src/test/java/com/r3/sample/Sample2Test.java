package com.r3.sample;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sample2Test {
    @Test
    void thirdTest() {
        assertEquals("UTC", System.getProperty("user.timezone"));
    }

    @Test
    void fourthTest() {
        assertEquals(BigInteger.TEN.longValue(), 10L);
    }
}
