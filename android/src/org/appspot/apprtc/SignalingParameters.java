package org.appspot.apprtc;

import java.util.List;

import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;

public class SignalingParameters {
	public final List<PeerConnection.IceServer> iceServers;

	public final MediaConstraints pcConstraints;

	public SignalingParameters(List<PeerConnection.IceServer> iceServers,
			MediaConstraints pcConstraints) {
		this.iceServers = iceServers;
		this.pcConstraints = pcConstraints;
	}
}