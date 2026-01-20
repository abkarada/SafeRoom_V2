package com.saferoom.webrtc.wrapper;

import dev.onvoid.webrtc.*;

public class WebRTCFactoryWrapper implements IWebRTCFactory {
    private final PeerConnectionFactory factory;

    public WebRTCFactoryWrapper(PeerConnectionFactory factory) {
        this.factory = factory;
    }

    @Override
    public IWebRTCPeerConnection createPeerConnection(RTCConfiguration config, PeerConnectionObserver observer) {
        RTCPeerConnection pc = factory.createPeerConnection(config, observer);
        return new WebRTCPeerConnectionWrapper(pc);
    }
}
