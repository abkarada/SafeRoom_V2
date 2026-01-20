package com.saferoom.webrtc.wrapper;

import dev.onvoid.webrtc.*;

public interface IWebRTCFactory {
    IWebRTCPeerConnection createPeerConnection(RTCConfiguration config, PeerConnectionObserver observer);
}
