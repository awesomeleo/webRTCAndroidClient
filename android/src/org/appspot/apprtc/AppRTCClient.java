package org.appspot.apprtc;

import java.util.LinkedList;

import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;

import android.app.Activity;
import android.os.AsyncTask;
import android.util.Log;

/**
 * Negotiates signaling for establishing a Web RTC communication between two peers. Invokes a very
 * little server that in turn uses web sockets.
 * <p>
 * To use: create an instance of this object (registering a message handler) and invoke
 * {@link #connect()}. Once that's done call {@link #sendMessage(String)} and wait for the
 * registered handler to be invoked with received messages.
 * 
 * TODO Rename this class to "SignalingClient" when we have more time to modify the ninja scripts!
 */
public class AppRTCClient {
	
	private static final String TAG = "SignalingClient";

	private GAEChannelClient webSocketClient;

	private final Activity activity;

	private final MessageHandler websocketMessageHandler;

	private final IceServersObserver iceServersObserver;

	// These members are only read/written under sendQueue's lock.
	private LinkedList<String> sendQueue = new LinkedList<String>();

	private SignalingParameters signalingParameters;

	public AppRTCClient(Activity activity, MessageHandler websocketMessageHandler,
			IceServersObserver iceServersObserver) {
		this.activity = activity;
		this.websocketMessageHandler = websocketMessageHandler;
		this.iceServersObserver = iceServersObserver;
	}

	/**
	 * Asynchronously connect to the signaling server and
	 * register message-handling callbacks on its web socket client.
	 */
	public void connect() {
		SignalingParameters params = getSignalingParameters();

		webSocketClient = new GAEChannelClient(activity, websocketMessageHandler);
		synchronized (sendQueue) {
			signalingParameters = params;
		}
		attemptToSendAllQueuedMessagesOnTheBackground();
		iceServersObserver.onIceServers(signalingParameters.iceServers);
	}

	/**
	 * Queue a message for sending to the room's channel and send it if already connected (other
	 * wise queued messages are drained when the channel is eventually established).
	 */
	public synchronized void sendMessage(String msg) {
		synchronized (sendQueue) {
			sendQueue.add(msg);
		}
		attemptToSendAllQueuedMessagesOnTheBackground();
	}

	public boolean isInitiator() {
		return webSocketClient.isInitiator();
	}

	public MediaConstraints pcConstraints() {
		return signalingParameters.pcConstraints;
	}
	
	/**
	 * Disconnect from the web socket
	 */
	public void disconnect() {
		if (webSocketClient != null) {
			webSocketClient.close();
			webSocketClient = null;
		}
	}
	
	private SignalingParameters getSignalingParameters() {
		LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<PeerConnection.IceServer>();
		iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
		 iceServers.add(new PeerConnection.IceServer("turn:computeengineondemand.appspot.com", "41784574", "4080218913"));

		MediaConstraints pcConstraints = new MediaConstraints();
		pcConstraints.optional
				.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
		pcConstraints.optional
				.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
		pcConstraints.optional
				.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
		pcConstraints.optional.add(new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));

		return new SignalingParameters(iceServers, pcConstraints);
	}
	
	private void attemptToSendAllQueuedMessagesOnTheBackground() {
		(new AsyncTask<Void, Void, Void>() {
			public Void doInBackground(Void... unused) {
				ifConnectedSendAllQueuedMessages();
				return null;
			}
		}).execute();
	}

	private void ifConnectedSendAllQueuedMessages() {
		synchronized (sendQueue) {
			if (signalingParameters == null) {
				return;
			}
			for (String msg : sendQueue) {
				Log.i(TAG, "Sending message: " + msg);
				webSocketClient.sendMessage(msg);
			}
			sendQueue.clear();
		}
	}
}
