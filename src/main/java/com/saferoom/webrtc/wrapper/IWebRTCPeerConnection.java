package com.saferoom.webrtc.wrapper;

import dev.onvoid.webrtc.*;

public interface IWebRTCPeerConnection {
    IWebRTCDataChannel createDataChannel(String label, RTCDataChannelInit init);

    void createOffer(RTCOfferOptions options, CreateSessionDescriptionObserver observer);

    void createAnswer(RTCAnswerOptions options, CreateSessionDescriptionObserver observer);

    void setLocalDescription(RTCSessionDescription desc, SetSessionDescriptionObserver observer);

    void setRemoteDescription(RTCSessionDescription desc, SetSessionDescriptionObserver observer);

    void addIceCandidate(RTCIceCandidate candidate);

    void close();
}
