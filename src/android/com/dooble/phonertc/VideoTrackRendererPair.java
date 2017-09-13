package com.dooble.phonertc;

import org.webrtc.VideoRenderer;
import org.webrtc.VideoTrack;

public class VideoTrackRendererPair {
	private VideoTrack _videoTrack;
	private VideoRenderer _videoRenderer;
	public boolean _isLocal;
	
	public VideoTrackRendererPair(VideoTrack videoTrack, VideoRenderer videoRenderer, boolean isLocal) {
		_videoTrack = videoTrack;
		_videoRenderer = videoRenderer;
		_isLocal = isLocal;
	}
	
	public VideoTrack getVideoTrack() {
		return _videoTrack;
	}

	public boolean checkisLocal() {
		return this._isLocal;
	}
	
	public void setVideoTrack(VideoTrack _videoTrack) {
		this._videoTrack = _videoTrack;
	}

	public VideoRenderer getVideoRenderer() {
		return _videoRenderer;
	}

	public void setVideoRenderer(VideoRenderer _videoRenderer) {
		this._videoRenderer = _videoRenderer;
	}
}
