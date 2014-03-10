package org.appspot.apprtc;
/**
 * Callback interface for messages delivered on a Web Socket.
 */
public interface MessageHandler {

	public void whenIJoinTheRoomCreatedByMe();

	public void whenAnotherPartyJoinedMyRoom();

	public void whenIJoinedARoomCreatedByAnotherParty();

	public void onMessage(String data);

	public void onClose();

	public void onError(int code, String description);
}