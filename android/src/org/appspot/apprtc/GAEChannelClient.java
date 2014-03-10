package org.appspot.apprtc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * A web socket client that uses a Web View to load a tiny HTML that contains the necessary
 * Javascript that opens the web socket and dispatches events to a global object named
 * "androidMessageHandler".
 * 
 * TODO Rename this class to "WebSocketClient" when we have more time to modify the ninja scripts!
 */
public class GAEChannelClient {
	private static final String SIGNALING_SERVER_URL = "http://192.168.1.6:2013";

	private static final String TAG = "WebSocketClient";

	private WebView webView;

	private final ProxyingMessageHandler proxyingMessageHandler;

	private boolean isInitiator;

	/** Asynchronously open an AppEngine channel. */
	@SuppressLint("SetJavaScriptEnabled")
	public GAEChannelClient(Activity activity, MessageHandler handler) {
		webView = new WebView(activity);
		webView.getSettings().setJavaScriptEnabled(true);
		webView.setWebChromeClient(new WebChromeClient() { // Purely for debugging.
			public boolean onConsoleMessage(ConsoleMessage msg) {
				Log.d(TAG,
						"console: " + msg.message() + " at " + msg.sourceId() + ":"
								+ msg.lineNumber());
				return false;
			}
		});
		webView.setWebViewClient(new WebViewClient() { // Purely for debugging.
			public void onReceivedError(WebView view, int errorCode, String description,
					String failingUrl) {
				Log.e(TAG, "JS error: " + errorCode + " in " + failingUrl + ", desc: "
						+ description);
			}
		});
		// inject the proxy into the Javascript context. It will be seen as another JS object...
		proxyingMessageHandler = new ProxyingMessageHandler(activity, handler);
		webView.addJavascriptInterface(proxyingMessageHandler, "androidMessageHandler");
		webView.loadUrl(SIGNALING_SERVER_URL);
	}

	/** Close the connection to the Web Socket. */
	public void close() {
		if (webView == null) {
			return;
		}
		proxyingMessageHandler.disconnect();
		webView.removeJavascriptInterface("androidMessageHandler");
		webView.loadUrl("about:blank");
		webView = null;
	}

	/**
	 * Pushes an object into the web socket
	 */
	public void sendMessage(String msg) {
		webView.loadUrl("javascript:sendMessage('" + msg + "')");
	}

	public boolean isInitiator() {
		return isInitiator;
	}

	/**
	 * Helper class for proxying callbacks from the Java<->JS interaction (private, background)
	 * thread to the Activity's UI thread.
	 */
	private class ProxyingMessageHandler {
		private final Activity activity;

		private final MessageHandler handler;

		private final boolean[] disconnected = { false };

		public ProxyingMessageHandler(Activity activity, MessageHandler handler) {
			this.activity = activity;
			this.handler = handler;
		}

		public void disconnect() {
			disconnected[0] = true;
		}

		private boolean disconnected() {
			return disconnected[0];
		}

		@JavascriptInterface
		public void whenIJoinTheRoomCreatedByMe(final boolean isInitiator) {
			GAEChannelClient.this.isInitiator = isInitiator;
			activity.runOnUiThread(new Runnable() {
				public void run() {
					if (!disconnected()) {
						handler.whenIJoinTheRoomCreatedByMe();
					}
				}
			});
		}

		@JavascriptInterface
		public void whenAnotherPartyJoinedMyRoom(final boolean isInitiator) {
			GAEChannelClient.this.isInitiator = isInitiator;
			activity.runOnUiThread(new Runnable() {
				public void run() {
					if (!disconnected()) {
						handler.whenAnotherPartyJoinedMyRoom();
					}
				}
			});
		}

		@JavascriptInterface
		public void whenIJoinedARoomCreatedByAnotherParty(final boolean isInitiator) {
			GAEChannelClient.this.isInitiator = isInitiator;
			activity.runOnUiThread(new Runnable() {
				public void run() {
					if (!disconnected()) {
						handler.whenIJoinedARoomCreatedByAnotherParty();
					}
				}
			});
		}

		@JavascriptInterface
		public void onMessage(final String data) {
			activity.runOnUiThread(new Runnable() {
				public void run() {
					if (!disconnected()) {
						handler.onMessage(data);
					}
				}
			});
		}

		@JavascriptInterface
		public void onClose() {
			activity.runOnUiThread(new Runnable() {
				public void run() {
					if (!disconnected()) {
						handler.onClose();
					}
				}
			});
		}

		@JavascriptInterface
		public void onError(final int code, final String description) {
			activity.runOnUiThread(new Runnable() {
				public void run() {
					if (!disconnected()) {
						handler.onError(code, description);
					}
				}
			});
		}
	}
}
