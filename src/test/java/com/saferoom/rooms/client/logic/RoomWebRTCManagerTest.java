package com.saferoom.rooms.client.logic;

import com.saferoom.rooms.grpc.SignalRelayRequest;
import com.saferoom.webrtc.wrapper.*;
import dev.onvoid.webrtc.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class RoomWebRTCManagerTest {

    @Mock
    private IWebRTCFactory factory;
    @Mock
    private IWebRTCPeerConnection pc;
    @Mock
    private IWebRTCDataChannel dc;
    @Mock
    private RoomWebRTCManager.Listener listener;

    private RoomWebRTCManagerImpl manager;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);

        // Mock Factory to return our Mock PC
        // Use any() safely on interfaces
        when(factory.createPeerConnection(any(RTCConfiguration.class), any(PeerConnectionObserver.class)))
                .thenReturn(pc);

        // Mock PC to return Mock DC
        when(pc.createDataChannel(anyString(), any(RTCDataChannelInit.class)))
                .thenReturn(dc);

        manager = new RoomWebRTCManagerImpl(factory);
        manager.setListener(listener);
    }

    @Test
    public void testInitiateConnectionCreatesOffer() {
        String remoteId = "remote-1";

        // Capture the CreateSessionDescriptionObserver passed to createOffer
        ArgumentCaptor<CreateSessionDescriptionObserver> captor = ArgumentCaptor
                .forClass(CreateSessionDescriptionObserver.class);

        manager.initiateConnection(remoteId);

        // Verify createDataChannels called (3 times for 3 channels)
        verify(pc, times(3)).createDataChannel(anyString(), any(RTCDataChannelInit.class));

        // Verify createOffer called
        verify(pc).createOffer(any(RTCOfferOptions.class), captor.capture());

        // Simulate Success callback
        RTCSessionDescription desc = new RTCSessionDescription(RTCSdpType.OFFER, "dummy-sdp");
        captor.getValue().onSuccess(desc);

        // Verify setLocalDescription called
        verify(pc).setLocalDescription(eq(desc), any(SetSessionDescriptionObserver.class));
    }

    @Test
    public void testHandleOfferCreatesAnswer() {
        String remoteId = "remote-1";
        String sdp = "offer-sdp";

        // Capture SetRemoteDescription Observer
        ArgumentCaptor<SetSessionDescriptionObserver> setRemoteCaptor = ArgumentCaptor
                .forClass(SetSessionDescriptionObserver.class);

        manager.handleOffer(remoteId, sdp);

        // Verify PeerConnection created
        verify(factory).createPeerConnection(any(RTCConfiguration.class), any(PeerConnectionObserver.class));

        // Verify setRemoteDescription
        verify(pc).setRemoteDescription(any(RTCSessionDescription.class), setRemoteCaptor.capture());

        // Simulate Remote Set Success
        setRemoteCaptor.getValue().onSuccess();

        // Now verify createAnswer is called
        verify(pc).createAnswer(any(RTCAnswerOptions.class), any(CreateSessionDescriptionObserver.class));
    }

    @Test
    public void testAddIceCandidateQueuesIfNotReady() {
        String remoteId = "remote-1";
        String candidate = "candidate:1";

        // 1. Create peer via handleOffer, but don't complete setRemoteDescription yet
        manager.handleOffer(remoteId, "sdp");

        // Verify peer created
        verify(factory).createPeerConnection(any(RTCConfiguration.class), any(PeerConnectionObserver.class));

        // Capture the SetRemoteDescription observer
        ArgumentCaptor<SetSessionDescriptionObserver> setRemoteCaptor = ArgumentCaptor
                .forClass(SetSessionDescriptionObserver.class);
        verify(pc).setRemoteDescription(any(RTCSessionDescription.class), setRemoteCaptor.capture());

        // 2. Add ICE candidate. Since observer.onSuccess() hasn't been called,
        // remoteSet is false.
        manager.addIceCandidate(remoteId, candidate, "audio", 0);

        // Verify it was NOT added to PC yet
        verify(pc, never()).addIceCandidate(any(RTCIceCandidate.class));

        // 3. Now complete the SetRemoteDescription
        setRemoteCaptor.getValue().onSuccess();

        // 4. Verify queue was flushed and added to PC
        verify(pc).addIceCandidate(any(RTCIceCandidate.class));
    }

    @Test
    public void testDisconnectClosesPC() {
        String remoteId = "remote-1";

        // Create context
        manager.initiateConnection(remoteId);

        manager.disconnect(remoteId);

        verify(pc).close();
    }
}
