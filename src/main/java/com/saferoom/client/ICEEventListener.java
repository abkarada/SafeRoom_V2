package com.saferoom.client;

import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;

/**
 * Listener for receiving asynchronous ICE lifecycle events.
 */
public interface ICEEventListener {

    void onLocalCandidateDiscovered(LocalCandidate candidate);

    void onRemoteCandidateAdded(RemoteCandidate candidate);

    void onGatheringComplete();

    void onIceStateChanged(IceProcessingState state);

    void onConnected(CandidatePair selectedPair);

    void onFailure(String reason);
}
