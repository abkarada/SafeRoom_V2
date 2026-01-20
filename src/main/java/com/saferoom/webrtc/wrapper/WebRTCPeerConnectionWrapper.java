package com.saferoom.webrtc.wrapper;

import dev.onvoid.webrtc.*;

public class WebRTCPeerConnectionWrapper implements IWebRTCPeerConnection {
    private final RTCPeerConnection pc;

    public WebRTCPeerConnectionWrapper(RTCPeerConnection pc) {
        this.pc = pc;
    }

    @Override
    public IWebRTCDataChannel createDataChannel(String label, RTCDataChannelInit init) {
        RTCDataChannel dc = pc.createDataChannel(label, init);
        return new WebRTCDataChannelWrapper(dc);
    }

    @Override
    public void createOffer(RTCOfferOptions options, CreateSessionDescriptionObserver observer) {
        pc.createOffer(options, observer);
    }

    @Override
    public void createAnswer(RTCAnswerOptions options, CreateSessionDescriptionObserver observer) {
        pc.createAnswer(options, observer);
    }

    @Override
    public void setLocalDescription(RTCSessionDescription desc, SetSessionDescriptionObserver observer) {
        pc.setLocalDescription(desc, observer);
    }

    @Override
    public void setRemoteDescription(RTCSessionDescription desc, SetSessionDescriptionObserver observer) {
        pc.setRemoteDescription(desc, observer);
    }

    @Override
    public void addIceCandidate(RTCIceCandidate candidate) {
        pc.addIceCandidate(candidate);
    }

    @Override
    public void close() {
        pc.close();
    }
}
