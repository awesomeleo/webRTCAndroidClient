package org.appspot.apprtc;

import java.util.List;

import org.webrtc.PeerConnection;

/**
 * Callback fired once the room's signaling parameters specify the set of ICE servers to use.
 */
public interface IceServersObserver {
	public void onIceServers(List<PeerConnection.IceServer> iceServers);
}