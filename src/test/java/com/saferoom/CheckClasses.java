package com.saferoom;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCPeerConnection;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

public class CheckClasses {
    @Test
    public void testModifiers() {
        check(PeerConnectionFactory.class);
        check(RTCPeerConnection.class);
    }

    private static void check(Class<?> clazz) {
        System.out.println("Checking " + clazz.getName());
        for (Method m : clazz.getDeclaredMethods()) {
            if (Modifier.isNative(m.getModifiers()) || Modifier.isFinal(m.getModifiers())) {
                System.out.println("  " + m.getName() + " is " +
                        (Modifier.isNative(m.getModifiers()) ? "NATIVE " : "") +
                        (Modifier.isFinal(m.getModifiers()) ? "FINAL " : ""));
            }
        }
    }
}
