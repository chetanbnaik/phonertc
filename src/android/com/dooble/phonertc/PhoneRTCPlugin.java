package com.dooble.phonertc;

/********** */
import java.nio.IntBuffer;
/********** */
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import android.util.Log;

import android.Manifest;
import android.app.Activity;
import android.graphics.Point;
import android.view.View;
import android.webkit.WebView;

//import android.widget.Button;
/********** */
import android.graphics.Bitmap;
import android.widget.TextView;
import android.opengl.GLException;

import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
/********** */

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;
import org.webrtc.RendererCommon;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import org.apache.cordova.PermissionHelper;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;

/*********** */
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL10;
/********** */

public class PhoneRTCPlugin extends CordovaPlugin {
	private AudioSource _audioSource;
	private AudioTrack _audioTrack;

	private VideoCapturerAndroid _videoCapturer;
	private VideoSource _videoSource;

	private PeerConnectionFactory _peerConnectionFactory;
	private Map<String, Session> _sessions;

	private VideoConfig _videoConfig;
	private VideoGLView _videoView;
	private List<VideoTrackRendererPair> _remoteVideos;
	private VideoTrackRendererPair _localVideo;
	private WebView.LayoutParams _videoParams;
	private boolean _shouldDispose = true;
	private boolean _initializedAndroidGlobals = false;
	private VideoCapturerAndroid.CameraSwitchHandler switchHandler;

	public CallbackContext callbackContext;
	private Bitmap snapshotBitmap;
  	private TextView scanText;

	protected final static String[] permissions = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE };

	public PhoneRTCPlugin() {
		_remoteVideos = new ArrayList<VideoTrackRendererPair>();
		_sessions = new HashMap<String, Session>();
	}

	@Override
	public boolean execute(String action, JSONArray args,
			CallbackContext callbackContext) throws JSONException {

		final CallbackContext _callbackContext = callbackContext;
		this.callbackContext = _callbackContext;

		if (action.equals("createSessionObject")) {
			final SessionConfig config = SessionConfig.fromJSON(args.getJSONObject(1));

			final String sessionKey = args.getString(0);
			_callbackContext.sendPluginResult(getSessionKeyPluginResult(sessionKey));

			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					if (!_initializedAndroidGlobals) {
						abortUnless(PeerConnectionFactory.initializeAndroidGlobals(cordova.getActivity(), true, true, true),
								"Failed to initializeAndroidGlobals");
						_initializedAndroidGlobals = true;
					}

					if (_peerConnectionFactory == null) {
						_peerConnectionFactory = new PeerConnectionFactory();
					}

					if (config.isAudioStreamEnabled() && _audioTrack == null) {
						initializeLocalAudioTrack();
					}

					if (config.isVideoStreamEnabled() && _localVideo == null) {
						initializeLocalVideoTrack();
					}

					_sessions.put(sessionKey, new Session(PhoneRTCPlugin.this,
							_callbackContext, config, sessionKey));

					if (_sessions.size() > 1) {
						_shouldDispose = false;
					}
				}
			});

			return true;
		} else if (action.equals("call")) {
			JSONObject container = args.getJSONObject(0);
			final String sessionKey = container.getString("sessionKey");

			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					try {
						if (_sessions.containsKey(sessionKey)) {
							_sessions.get(sessionKey).call();
							_callbackContext.success();
						} else {
							_callbackContext.error("No session found matching the key: '" + sessionKey + "'");
						}
					} catch(Exception e) {
						_callbackContext.error(e.getMessage());
					}
				}
			});

			return true;
		} else if (action.equals("receiveMessage")) {
			JSONObject container = args.getJSONObject(0);
			final String sessionKey = container.getString("sessionKey");
			final String message = container.getString("message");

			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					Session session = _sessions.get(sessionKey);
					if (null != session) {
						session.receiveMessage(message);
					}
				}
			});

			return true;
		} else if (action.equals("renegotiate")) {
			JSONObject container = args.getJSONObject(0);
			final String sessionKey = container.getString("sessionKey");
			final SessionConfig config = SessionConfig.fromJSON(container.getJSONObject("config"));

			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					Session session = _sessions.get(sessionKey);
					session.setConfig(config);
					session.createOrUpdateStream();
				}
			});

		} else if (action.equals("disconnect")) {
			JSONObject container = args.getJSONObject(0);
			final String sessionKey = container.getString("sessionKey");

			cordova.getActivity().runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (_sessions.containsKey(sessionKey)) {
						_sessions.get(sessionKey).disconnect(true);
					}
				}
			});

			return true;
		} else if (action.equals("setVideoView")) {
			_videoConfig = VideoConfig.fromJSON(args.getJSONObject(0));

			// make sure it's not junk
			if (_videoConfig.getContainer().getWidth() == 0 || _videoConfig.getContainer().getHeight() == 0) {
				Log.d("com.packetservo","junk video container");
				return false;
			}

			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					if (!_initializedAndroidGlobals) {
						abortUnless(PeerConnectionFactory.initializeAndroidGlobals(cordova.getActivity(), true, true, true),
								"Failed to initializeAndroidGlobals");
						_initializedAndroidGlobals = true;
					}

					if (_peerConnectionFactory == null) {
						_peerConnectionFactory = new PeerConnectionFactory();
					}

					_videoParams = new WebView.LayoutParams(
							(int)(_videoConfig.getContainer().getWidth() * _videoConfig.getDevicePixelRatio()),
							(int)(_videoConfig.getContainer().getHeight() * _videoConfig.getDevicePixelRatio()),
							(int)(_videoConfig.getContainer().getX() * _videoConfig.getDevicePixelRatio()),
							(int)(_videoConfig.getContainer().getY() * _videoConfig.getDevicePixelRatio()));

					if (_videoView == null) {
						// createVideoView();

						if (_videoConfig.getLocal() != null && _localVideo == null) {
							initializeLocalVideoTrack();
						}
					} else {
						_videoView.setLayoutParams(_videoParams);
					}
				}
			});

			return true;
		} else if (action.equals("hideVideoView")) {
			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					if (_videoView != null) {
						_videoView.setVisibility(View.GONE);
					}
				}
			});
		} else if (action.equals("showVideoView")) {
			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					if (_videoView != null) {
						_videoView.setVisibility(View.VISIBLE);
					}
				}
			});
		} else if (action.equals("checkPermissions")){
			if(PermissionHelper.hasPermission(this, permissions[0]) && PermissionHelper.hasPermission(this, permissions[1]) && PermissionHelper.hasPermission(this, permissions[2])) {
				callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
				return true;
			}
			else{
				try {
					PermissionHelper.requestPermissions(this, 0, permissions);
					return true;
				}
				catch (Exception e) {
					callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
				}
			}
		} else if (action.equals("switchCamera")) {
			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run(){
					if (_videoCapturer == null) {return;}
					//if (_videoCapturer.isDisposed) return;
					if (_localVideo == null) {return;}
					switchCamera();
				}
			});
		} else if (action.equals("scanQR")) {
			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					if (_videoView == null) {return;}

					captureBitmap(new BitmapReadyCallbacks(){
						@Override
						public void onBitmapReady(Bitmap bitmap) {
							int[] intArray = new int[bitmap.getWidth()*bitmap.getHeight()];
							bitmap.getPixels(intArray, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
							LuminanceSource source = new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(),intArray);
							BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
							Reader reader = new MultiFormatReader();
							try {
								Result result = reader.decode(binaryBitmap);
								scanText.setText(result.toString());
							} catch (NotFoundException e) {e.printStackTrace();}
							catch (ChecksumException e) {e.printStackTrace();}
							catch (FormatException e) {e.printStackTrace();}
						}
					});
				}
			});
			return true;
		}

		callbackContext.error("Invalid action: " + action);
		return false;
	}

	void initializeLocalVideoTrack() {
		_videoCapturer = getVideoCapturer();
		_videoSource = _peerConnectionFactory.createVideoSource(_videoCapturer,
				new MediaConstraints());
		_localVideo = new VideoTrackRendererPair(_peerConnectionFactory.createVideoTrack("ARDAMSv0", _videoSource), null, true);
		refreshVideoView();
	}

	int getPercentage(int localValue, int containerValue) {
		return (int)(localValue * 100.0 / containerValue);
	}

	void initializeLocalAudioTrack() {
		_audioSource = _peerConnectionFactory.createAudioSource(new MediaConstraints());
		_audioTrack = _peerConnectionFactory.createAudioTrack("ARDAMSa0", _audioSource);
	}

	public VideoTrack getLocalVideoTrack() {
		if (_localVideo == null) {
			return null;
		}

		return _localVideo.getVideoTrack();
	}

	public AudioTrack getLocalAudioTrack() {
		return _audioTrack;
	}

	public PeerConnectionFactory getPeerConnectionFactory() {
		return _peerConnectionFactory;
	}

	public Activity getActivity() {
		return cordova.getActivity();
	}

	public WebView getWebView() {
		return this.getWebView();
	}

	public VideoConfig getVideoConfig() {
		return this._videoConfig;
	}

	private static void abortUnless(boolean condition, String msg) {
		if (!condition) {
			throw new RuntimeException(msg);
		}
	}

	private void switchCamera() {
		Log.d("com.packetservo", "switching camera..");
		_videoCapturer.switchCamera(null);
	}

	// Cycle through likely device names for the camera and return the first
	// capturer that works, or crash if none do.
	private VideoCapturerAndroid getVideoCapturer() {
		String[] cameraFacing = { "back", "front" };
		int[] cameraIndex = { 1, 0 };
		int[] cameraOrientation = { 0, 90, 180, 270 };
		for (String facing : cameraFacing) {
			for (int index : cameraIndex) {
				for (int orientation : cameraOrientation) {
					String name = "Camera " + index + ", Facing " + facing +
						", Orientation " + orientation;
					VideoCapturerAndroid capturer = VideoCapturerAndroid.create(name, null);
					if (capturer != null) {
						// logAndToast("Using camera: " + name);
						return capturer;
					}
				}
			}
		}
		throw new RuntimeException("Failed to open capturer");
	}

	public void addRemoteVideoTrack(VideoTrack videoTrack, boolean isLocal) {
		_remoteVideos.add(new VideoTrackRendererPair(videoTrack, null, isLocal));
		refreshVideoView();
	}

	public void removeRemoteVideoTrack(VideoTrack videoTrack) {
		for (VideoTrackRendererPair pair : _remoteVideos) {
			if (pair.getVideoTrack() == videoTrack) {
				if (pair.getVideoRenderer() != null) {
					pair.getVideoTrack().removeRenderer(pair.getVideoRenderer());
					pair.setVideoRenderer(null);
				}

				pair.setVideoTrack(null);

				_remoteVideos.remove(pair);
				refreshVideoView();
				return;
			}
		}
	}

	private interface BitmapReadyCallbacks {
		void onBitmapReady(Bitmap bitmap);
	}

	private void captureBitmap(final BitmapReadyCallbacks bitmapReadyCallbacks){
		if (_videoView == null) { return; }
		_videoView.queueEvent(new Runnable() {
		@Override
		public void run() {
			EGL10 egl = (EGL10) EGLContext.getEGL();
			GL10 gl = (GL10)egl.eglGetCurrentContext().getGL();
			snapshotBitmap = createBitmapFromGLSurface(0,0,_videoView.getWidth(),_videoView.getHeight(),gl);

			cordova.getActivity().runOnUiThread(new Runnable() {
				@Override
				public void run() {
					bitmapReadyCallbacks.onBitmapReady(snapshotBitmap);
				}
			});
		}
		});
	}

	private Bitmap createBitmapFromGLSurface(int x, int y, int w, int h, GL10 gl) throws OutOfMemoryError {
		int bitmapBuffer[] = new int[w * h];
		int bitmapSource[] = new int[w * h];
		IntBuffer intBuffer = IntBuffer.wrap(bitmapBuffer);
		intBuffer.position(0);

		try {
			gl.glReadPixels(x, y, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, intBuffer);
			int offset1, offset2;
			for (int i = 0; i < h; i++) {
				offset1 = i * w;
				offset2 = (h - i - 1) * w;
				for (int j = 0; j < w; j++) {
				int texturePixel = bitmapBuffer[offset1 + j];
				int blue = (texturePixel >> 16) & 0xff;
				int red = (texturePixel << 16) & 0x00ff0000;
				int pixel = (texturePixel & 0xff00ff00) | red | blue;
				bitmapSource[offset2 + j] = pixel;
				}
			}
		} catch (GLException e) {
			Log.e("com.packetservo", "createBitmapFromGLSurface: " + e.getMessage(), e);
			return null;
		}

		return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888);
	}

	private void createVideoView() {
		Point size = new Point();
		size.set((int)(_videoConfig.getContainer().getWidth() * _videoConfig.getDevicePixelRatio()),
				(int)(_videoConfig.getContainer().getHeight() * _videoConfig.getDevicePixelRatio()));

		_videoView = new VideoGLView(cordova.getActivity(), size);
		VideoRendererGui.setView(_videoView, null);
		scanText = new TextView(cordova.getActivity());
    	scanText.setText("Scan results");
		((WebView) webView.getView()).addView(_videoView, _videoParams);
		((WebView) webView.getView()).addView(scanText, _videoParams);
	}

	private void refreshVideoView() {
		int n = _remoteVideos.size();

		for (VideoTrackRendererPair pair : _remoteVideos) {
			if (pair.getVideoRenderer() != null) {
				pair.getVideoTrack().removeRenderer(pair.getVideoRenderer());
			}

			pair.setVideoRenderer(null);
		}

		if (_localVideo != null && _localVideo.getVideoRenderer() != null) {
			_localVideo.getVideoTrack().removeRenderer(_localVideo.getVideoRenderer());
			_localVideo.setVideoRenderer(null);
		}

		if (_videoView != null) {
			((WebView) webView.getView()).removeView(_videoView);
			_videoView = null;
		}

		if (n == 1) {
			createVideoView();
			VideoTrackRendererPair pair = _remoteVideos.get(0);
			pair.setVideoRenderer(new VideoRenderer(
				VideoRendererGui.create(_videoConfig.getContainer().getX(),
					_videoConfig.getContainer().getY(),
					100, //_videoConfig.getContainer().getWidth(),
					100, //_videoConfig.getContainer().getHeight(),
					RendererCommon.ScalingType.SCALE_ASPECT_FILL, false)
			));
			pair.getVideoTrack().addRenderer(pair.getVideoRenderer());
		}

		if (n > 1) {
			
			createVideoView();

			int rows = n < 9 ? 2 : 3;
			int videosInRow = n == 2 ? 2 : (int)Math.ceil((float)n / rows);

			int videoSize = (int)((float)_videoConfig.getContainer().getWidth() / videosInRow);
			int actualRows = (int)Math.ceil((float)n / videosInRow);

			int y = getCenter(actualRows, videoSize, _videoConfig.getContainer().getHeight());

			int videoIndex = 0;
			int videoSizeAsPercentage = getPercentage(videoSize, _videoConfig.getContainer().getWidth());

			for (int row = 0; row < rows && videoIndex < n; row++) {
				int x = getCenter(row < row - 1 || n % rows == 0 ?
									videosInRow : n - (Math.min(n, videoIndex + videosInRow) - 1),
								videoSize,
								_videoConfig.getContainer().getWidth());

				for (int video = 0; video < videosInRow && videoIndex < n; video++) {
					VideoTrackRendererPair pair = _remoteVideos.get(videoIndex++);

                    int widthPercentage = videoSizeAsPercentage;
                    int heightPercentage = videoSizeAsPercentage;
                    if((x + widthPercentage) > 100){
                        widthPercentage = widthPercentage - x;
                    }
                    if((y + heightPercentage) > 100){
						heightPercentage = heightPercentage - y;
                    }

					pair.setVideoRenderer(new VideoRenderer(
							VideoRendererGui.create(x, y, widthPercentage, heightPercentage,
									RendererCommon.ScalingType.SCALE_ASPECT_FIT, true)));

					pair.getVideoTrack().addRenderer(pair.getVideoRenderer());

					x += videoSizeAsPercentage;
				}

				y += getPercentage(videoSize, _videoConfig.getContainer().getHeight());
			}

			if (_videoConfig.getLocal() != null && _localVideo != null) {
				_localVideo.getVideoTrack().addRenderer(new VideoRenderer(
						VideoRendererGui.create(getPercentage(_videoConfig.getLocal().getX(), _videoConfig.getContainer().getWidth()),
												getPercentage(_videoConfig.getLocal().getY(), _videoConfig.getContainer().getHeight()),
												getPercentage(_videoConfig.getLocal().getWidth(), _videoConfig.getContainer().getWidth()),
												getPercentage(_videoConfig.getLocal().getHeight(), _videoConfig.getContainer().getHeight()),
								RendererCommon.ScalingType.SCALE_ASPECT_FILL,
												true)));

			}
		}
	}

	int getCenter(int videoCount, int videoSize, int containerSize) {
		return getPercentage((int)Math.round((containerSize - videoSize * videoCount) / 2.0), containerSize);
	}

	PluginResult getSessionKeyPluginResult(String sessionKey) throws JSONException {
		JSONObject json = new JSONObject();
		json.put("type", "__set_session_key");
		json.put("sessionKey", sessionKey);

		PluginResult result = new PluginResult(PluginResult.Status.OK, json);
		result.setKeepCallback(true);

		return result;
	}

	public void onSessionDisconnect(String sessionKey) {
		_sessions.remove(sessionKey);


		if (_sessions.size() == 0) {
			cordova.getActivity().runOnUiThread(new Runnable() {
				public void run() {
					if (_localVideo != null ) {
						if (_localVideo.getVideoTrack() != null && _localVideo.getVideoRenderer() != null) {
							_localVideo.getVideoTrack().removeRenderer(_localVideo.getVideoRenderer());
						}

						_localVideo = null;
					}

					if (_videoView != null) {
						_videoView.setVisibility(View.GONE);
						((WebView) webView.getView()).removeView(_videoView);
						((WebView) webView.getView()).removeView(scanText);
					}

					if (_videoSource != null) {
						if (_shouldDispose) {
							_videoSource.dispose();
						} else {
							_videoSource.stop();
						}

						_videoSource = null;
					}

					if (_videoCapturer != null) {
						try {
							_videoCapturer.dispose();
						}catch (Exception e){}
						_videoCapturer = null;
					}

                    if (_audioSource != null) {
                        _audioSource.dispose();
                        _audioSource = null;

                        _audioTrack = null;
                    }

					// if (_peerConnectionFactory != null) {
					// 	_peerConnectionFactory.dispose();
					// 	_peerConnectionFactory = null;
					// }

					_remoteVideos.clear();
					_shouldDispose = true;
				}
			});
		}
	}

	public boolean shouldDispose() {
		return _shouldDispose;
	}


	public void onRequestPermissionResult(int requestCode, String[] permissions,
										  int[] grantResults) throws JSONException
	{
		for(int r:grantResults)
		{
			if(r == PackageManager.PERMISSION_DENIED)
			{
				this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, 20));
				return;
			}
		}
		this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
	}
}
