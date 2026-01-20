package com.saferoom.webrtc.wrapper;

import dev.onvoid.webrtc.*;

public class WebRTCDataChannelWrapper implements IWebRTCDataChannel {
    private final RTCDataChannel dc;

    public WebRTCDataChannelWrapper(RTCDataChannel dc) {
        this.dc = dc;
    }

    @Override
    public void registerObserver(RTCDataChannelObserver observer) {
        dc.registerObserver(observer);
    }

    @Override
    public RTCDataChannelState getState() {
        return dc.getState();
    }

    @Override
    public String getLabel() {
        return dc.getLabel();
    }

    @Override
    public void close() {
        dc.close();
    }

    @Override
    public void send(RTCDataChannelBuffer buffer) {
        try {
            dc.send(buffer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send data: " + e.getMessage(), e);
        }
    }
}
