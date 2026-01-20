package com.saferoom.webrtc.wrapper;

import dev.onvoid.webrtc.*;

public interface IWebRTCDataChannel {
    void registerObserver(RTCDataChannelObserver observer);

    RTCDataChannelState getState();

    String getLabel();

    void close();

    void send(RTCDataChannelBuffer buffer);
}
