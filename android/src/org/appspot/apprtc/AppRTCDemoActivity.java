package org.appspot.apprtc;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DataChannel.Buffer;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Main Activity of the WebRTCAndroidClient Android app enabling two Android devices to send
 * messages to each other, natively, using WebRTC Data Channels. This is possible due to the use of
 * the Android/Java implementation of PeerConnection and a very tiny signaling server made with
 * Node.js. TODO Rename this class to "WebRTCAndroidClientActivity" when we have more time to modify
 * the ninja scripts!
 */
public class AppRTCDemoActivity extends Activity implements IceServersObserver {
	private static final String TAG = "WebRTCAndroidClientActivity";

	private PeerConnectionFactory factory;

	private PeerConnection pc;

	private final PeerConnectionObserver pcObserver = new PeerConnectionObserver();

	private final SessionDescriptionProtocolObserver sdpObserver = new SessionDescriptionProtocolObserver();

	private final MessageHandler websocketMessageHandler = new WebsocketMessageHandler();

	private AppRTCClient signalingClient = new AppRTCClient(this, websocketMessageHandler, this);

	private Toast logToast;

	private LinkedList<IceCandidate> queuedRemoteCandidates = new LinkedList<IceCandidate>();

	// Synchronize on quit[0] to avoid teardown-related crashes.
	private final Boolean[] quit = new Boolean[] { false };

	private DataChannel dataChannel;

	private EditText commandEditText;

	private Button submitButton;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(this));

		initializeView();

		abortUnless(PeerConnectionFactory.initializeAndroidGlobals(this),
				"Failed to initializeAndroidGlobals");

		logAndToast("Connecting to room...");
		signalingClient.connect();
	}

	private void initializeView() {
		// getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		commandEditText = new EditText(this);
		commandEditText.setWidth(380);
		commandEditText.setText("Your command here");
		commandEditText.setSelection(commandEditText.getText().length());

		submitButton = new Button(this);
		submitButton.setText("Wait...");
		submitButton.setEnabled(false); // as the signaling needs to take place first
		submitButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				CharSequence text = commandEditText.getText();
				if (text != null && text.length() > 0) {
					String command = text.toString();
					logAndToast("Sending \"" + command + "\"...");
					sendThroughDataChannel(command);
				}
			}
		});
		LinearLayout layout = new LinearLayout(this);
		layout.addView(commandEditText);
		layout.addView(submitButton);
		setContentView(layout);
	}

	private void sendThroughDataChannel(String command) {
		dataChannel.send(new Buffer(ByteBuffer.wrap(command.getBytes(Charset.forName("UTF-8"))),
				false));
	}

	private void createDataChannel() {
		dataChannel = pc.createDataChannel("commands", new DataChannel.Init());
		dataChannel.registerObserver(new DataChannel.Observer() {
			@Override
			public void onStateChange() {
				Log.d(TAG, "DC is " + dataChannel.state());
			}

			@Override
			public void onMessage(final Buffer buffer) {
				byte[] msgAsByteArray = new byte[buffer.data.capacity()];
				buffer.data.get(msgAsByteArray);
				String command = new String(msgAsByteArray, Charset.forName("UTF-8"));
				final String msg = "Received \"" + command + "\"";
				Log.i(TAG, msg);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						Toast.makeText(AppRTCDemoActivity.this, msg, Toast.LENGTH_SHORT).show();
					}
				});
			}
		});
	}

	@Override
	public void onIceServers(List<PeerConnection.IceServer> iceServers) {
		factory = new PeerConnectionFactory();

		MediaConstraints pcConstraints = signalingClient.pcConstraints();
		pc = factory.createPeerConnection(iceServers, pcConstraints, pcObserver);

		createDataChannel();

		logAndToast("Waiting for ICE candidates...");
	}

	@Override
	protected void onDestroy() {
		disconnectAndExit();
		super.onDestroy();
	}

	/**
	 * Poor-man's assert(): die with |msg| unless |condition| is true.
	 */
	private static void abortUnless(boolean condition, String msg) {
		if (!condition) {
			throw new RuntimeException(msg);
		}
	}

	/**
	 * Log |msg| and Toast about it.
	 */
	private void logAndToast(String msg) {
		Log.i(TAG, msg);
		if (logToast != null) {
			logToast.cancel();
		}
		logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
		logToast.show();
	}

	/**
	 * Send |json| to the underlying Signaling client
	 */
	private void sendMessage(JSONObject json) {
		signalingClient.sendMessage(json.toString());
	}

	/**
	 * Put a |key|->|value| mapping in |json|.
	 */
	private static void jsonPut(JSONObject json, String key, Object value) {
		try {
			json.put(key, value);
		}
		catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * observe ICE & stream changes and react accordingly.
	 */
	private class PeerConnectionObserver implements PeerConnection.Observer {

		@Override
		public void onIceCandidate(final IceCandidate candidate) {
			runOnUiThread(new Runnable() {
				public void run() {
					JSONObject json = new JSONObject();
					jsonPut(json, "type", "candidate");
					jsonPut(json, "label", candidate.sdpMLineIndex);
					jsonPut(json, "id", candidate.sdpMid);
					jsonPut(json, "candidate", candidate.sdp);
					sendMessage(json);
				}
			});
		}

		@Override
		public void onError() {
			runOnUiThread(new Runnable() {
				public void run() {
					String errorMessage = "PeerConnection error!";
					logAndToast(errorMessage);
					throw new RuntimeException(errorMessage);
				}
			});
		}

		@Override
		public void onSignalingChange(PeerConnection.SignalingState newState) {
			Log.d(TAG, "Signaling state has changed to " + newState);
		}

		@Override
		public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
			Log.d(TAG, "ICE Connection state has changed to " + newState);
		}

		@Override
		public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
			Log.d(TAG, "ICE Gathering state has changed to " + newState);
		}

		@Override
		public void onAddStream(final MediaStream stream) {
			Log.d(TAG, "A stream has been added");
		}

		@Override
		public void onRemoveStream(final MediaStream stream) {
			Log.d(TAG, "A stream has been removed");
		}

		@Override
		public void onDataChannel(final DataChannel dc) {
			Log.d(TAG, "Data Channel " + dc.label() + " has been created");
		}

		@Override
		public void onRenegotiationNeeded() {
			Log.d(TAG, "PeerConnectionObserver::onRenegotiationNeeded() invoked");
			// No need to do anything
		}
	}

	/**
	 * Handle offer creation/signaling and answer setting, as well as adding remote ICE candidates
	 * once the answer SDP is set.
	 */
	private class SessionDescriptionProtocolObserver implements SdpObserver {

		/** Called on success of Create{Offer,Answer}(). */
		@Override
		public void onCreateSuccess(final SessionDescription localDescription) {
			runOnUiThread(new Runnable() {
				public void run() {
					Log.i(TAG, "Setting local description (" + localDescription.type + ")");
					pc.setLocalDescription(sdpObserver, localDescription);
				}
			});
		}

		/** Called on success of Set{Local,Remote}Description(). */
		@Override
		public void onSetSuccess() {
			runOnUiThread(new Runnable() {
				public void run() {
					if (signalingClient.isInitiator()) {
						if (pc.getRemoteDescription() != null) {
							// We've set our local offer and received & set the remote answer
							addAllRemoteCandidatesToPeerConnection();
						} else {
							// We've just set our local description on the OFFER, so time to send
							// it.
							Log.i(TAG, "Sending local description to the other party (OFFER)");
							sendLocalDescription();
						}
					} else {
						if (pc.getLocalDescription() == null) {
							// The remote offer is set, time to create our answer.
							logAndToast("Creating answer...");
							pc.createAnswer(SessionDescriptionProtocolObserver.this,
									signalingClient.pcConstraints());
						} else {
							// We've just set our local description on the ANSWER, so time to send
							// it.
							Log.i(TAG, "Sending local description to the other party (ANSWER)");
							sendLocalDescription();
							addAllRemoteCandidatesToPeerConnection();
						}
					}
				}
			});
		}

		/** Called on error of Create{Offer,Answer}(). */
		@Override
		public void onCreateFailure(final String error) {
			runOnUiThread(new Runnable() {
				public void run() {
					String errorMessage = "createSDP error: " + error;
					logAndToast(errorMessage);
					throw new RuntimeException(errorMessage);
				}
			});
		}

		/** Called on error of Set{Local,Remote}Description(). */
		@Override
		public void onSetFailure(final String error) {
			runOnUiThread(new Runnable() {
				public void run() {
					String errorMessage = "setSDP error: " + error;
					logAndToast(errorMessage);
					throw new RuntimeException(errorMessage);
				}
			});
		}

		/**
		 * Sends local SDP (offer or answer, depending on role) to the other participant.
		 */
		private void sendLocalDescription() {
			SessionDescription sdp = pc.getLocalDescription();
			logAndToast("Sending " + sdp.type);
			JSONObject json = new JSONObject();
			jsonPut(json, "type", sdp.type.canonicalForm());
			jsonPut(json, "sdp", sdp.description);
			sendMessage(json);
		}

		private void addAllRemoteCandidatesToPeerConnection() {
			for (IceCandidate candidate : queuedRemoteCandidates) {
				Log.i(TAG, "Adding Ice Candidate to my peer connection: " + candidate);
				pc.addIceCandidate(candidate);
			}
			queuedRemoteCandidates = null;
			Toast.makeText(AppRTCDemoActivity.this, "Now you can send a message", Toast.LENGTH_LONG)
					.show();
			submitButton.setText("Send");
			submitButton.setEnabled(true);
		}
	}

	private class WebsocketMessageHandler implements MessageHandler {

		@JavascriptInterface
		public void whenIJoinTheRoomCreatedByMe() {
			Log.i(TAG, "Waiting until another party arrives...");
		}

		@JavascriptInterface
		public void whenAnotherPartyJoinedMyRoom() {
			Log.i(TAG, "Another party has just joined the room");

			// safety check as this message should be delivered only to me.
			if (signalingClient.isInitiator()) {
				logAndToast("Creating offer...");
				pc.createOffer(sdpObserver, signalingClient.pcConstraints());
			}
		}

		@JavascriptInterface
		public void whenIJoinedARoomCreatedByAnotherParty() {
			Log.i(TAG, "Waiting for the room creator to send me an offer");
		}

		@JavascriptInterface
		public void onMessage(String data) {
			try {
				JSONObject json = new JSONObject(data);
				String type = (String) json.get("type");
				if (type.equals("candidate")) {
					IceCandidate candidate = new IceCandidate((String) json.get("id"),
							json.getInt("label"), (String) json.get("candidate"));

					if (queuedRemoteCandidates != null) {
						Log.d(TAG, "Adding a remote candidate to my queue");
						queuedRemoteCandidates.add(candidate);
					} else {
						Log.d(TAG,
								"Adding a remote candidate directly to the connection, as the queue was null");
						pc.addIceCandidate(candidate);
					}
				} else if (type.equals("answer") || type.equals("offer")) {
					Log.i(TAG, "Setting remote description of type " + type);

					SessionDescription sdp = new SessionDescription(
							SessionDescription.Type.fromCanonicalForm(type),
							(String) json.get("sdp"));
					pc.setRemoteDescription(sdpObserver, sdp);
				} else if (type.equals("bye")) {
					Log.i(TAG, "Remote end hung up; dropping PeerConnection");
					disconnectAndExit();
				} else {
					String errorMessage = "Unexpected message: " + data;
					Log.e(TAG, errorMessage);
					throw new RuntimeException(errorMessage);
				}
			}
			catch (JSONException e) {
				throw new RuntimeException(e);
			}
		}

		@JavascriptInterface
		public void onClose() {
			disconnectAndExit();
		}

		@JavascriptInterface
		public void onError(int code, String description) {
			disconnectAndExit();
		}
	}

	// Disconnect from remote resources, dispose of local resources, and exit.
	private void disconnectAndExit() {
		synchronized (quit[0]) {
			if (quit[0]) {
				return;
			}
			quit[0] = true;
			if (pc != null) {
				pc.dispose();
				pc = null;
			}
			if (signalingClient != null) {
				signalingClient.sendMessage("{\"type\": \"bye\"}");
				signalingClient.disconnect();
				signalingClient = null;
			}
			if (factory != null) {
				factory.dispose();
				factory = null;
			}
			if (dataChannel != null) {
				dataChannel.close();
				dataChannel.dispose();
				dataChannel = null;
			}
			finish();
		}
	}
}
