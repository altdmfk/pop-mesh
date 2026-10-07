package com.pop.gateway;

import org.junit.jupiter.api.BeforeAll;
import reactor.blockhound.BlockHound;

public abstract class BaseBlockHoundTest {

    @BeforeAll
    public static void setupBlockHound() {
        try {
            BlockHound.builder()
                    .allowBlockingCallsInside("java.util.UUID", "randomUUID") // Common JDK blocking
                    .allowBlockingCallsInside("java.io.FileInputStream", "readBytes") // Sometimes needed for classloading
                    .install();
        } catch (Throwable ignored) {
            // Already installed by BlockHound JUnit platform listener
        }
    }
}
