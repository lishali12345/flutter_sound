package com.dooboolab.fluttersound;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/** FlutterSoundPlugin */
public class FlutterSoundPlugin implements MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, AudioInterface{
  final static String TAG = "FlutterSoundPlugin";
  final static String RECORD_STREAM = "com.dooboolab.fluttersound/record";
  final static String PLAY_STREAM= "com.dooboolab.fluttersound/play";

  private static final String ERR_UNKNOWN = "ERR_UNKNOWN";
  private static final String ERR_PLAYER_IS_NULL = "ERR_PLAYER_IS_NULL";
  private static final String ERR_PLAYER_IS_PLAYING = "ERR_PLAYER_IS_PLAYING";
  private static final String ERR_RECORDER_IS_NULL = "ERR_RECORDER_IS_NULL";
  private static final String ERR_RECORDER_IS_RECORDING = "ERR_RECORDER_IS_RECORDING";

  private final ExecutorService taskScheduler = Executors.newSingleThreadExecutor();

  private static Registrar reg;
  final private AudioModel model = new AudioModel();
  private Timer mTimer = new Timer();
  final private Handler recordHandler = new Handler();
  //mainThread handler
  final private Handler mainHandler = new Handler();
  final private Handler dbPeakLevelHandler = new Handler();
  private static MethodChannel channel;

  /** Plugin registration. */
  public static void registerWith(Registrar registrar) {
    channel = new MethodChannel(registrar.messenger(), "flutter_sound");
    channel.setMethodCallHandler(new FlutterSoundPlugin());
    reg = registrar;
  }

  @Override
  public void onMethodCall(final MethodCall call, final Result result) {
    final String path = call.argument("path");
    switch (call.method) {
      case "startRecorder":
        taskScheduler.submit(() -> {
          int sampleRate = call.argument("sampleRate");
          int numChannels = call.argument("numChannels");
          int androidEncoder = call.argument("androidEncoder");
          Integer bitRate = call.argument("bitRate");
          int androidAudioSource = call.argument("androidAudioSource");
          int androidOutputFormat = call.argument("androidOutputFormat");
          startRecorder(numChannels, sampleRate, bitRate, androidEncoder, androidAudioSource, androidOutputFormat, path, result);
        });
        break;
      case "stopRecorder":
        taskScheduler.submit(() -> stopRecorder(result));
        break;
      case "startPlayer":
        this.startPlayer(path, result);
        break;
      case "stopPlayer":
        this.stopPlayer(result);
        break;
      case "pausePlayer":
        this.pausePlayer(result);
        break;
      case "resumePlayer":
        this.resumePlayer(result);
        break;
      case "seekToPlayer":
        int sec = call.argument("sec");
        this.seekToPlayer(sec, result);
        break;
      case "setVolume":
        double volume = call.argument("volume");
        this.setVolume(volume, result);
        break;
      case "setDbPeakLevelUpdate":
        double intervalInSecs = call.argument("intervalInSecs");
        this.setDbPeakLevelUpdate(intervalInSecs, result);
        break;
      case "setDbLevelEnabled":
        boolean enabled = call.argument("enabled");
        this.setDbLevelEnabled(enabled, result);
        break;
      case "setSubscriptionDuration":
        if (call.argument("sec") == null) return;
        double duration = call.argument("sec");
        this.setSubscriptionDuration(duration, result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    switch (requestCode) {
      case REQUEST_RECORD_AUDIO_PERMISSION:
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
          return true;
        break;
    }
    return false;
  }

  @Override
  public void startRecorder(int numChannels, int sampleRate, Integer bitRate, int androidEncoder, int androidAudioSource, int androidOutputFormat, String path, final Result result) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

      if (
          reg.activity().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
              || reg.activity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
          ) {
        reg.activity().requestPermissions(new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        }, 0);
        result.error(TAG, "NO PERMISSION GRANTED", Manifest.permission.RECORD_AUDIO + " or " + Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return;
      }
    }

    if (path == null) {
      path = AudioModel.DEFAULT_FILE_LOCATION;
    } else {
      path = Environment.getExternalStorageDirectory().getPath() + "/" + path;
    }

    if (this.model.getMediaRecorder() == null) {
      this.model.setMediaRecorder(new MediaRecorder());
      this.model.getMediaRecorder().setAudioSource(androidAudioSource);
      this.model.getMediaRecorder().setOutputFormat(androidOutputFormat);
      this.model.getMediaRecorder().setAudioEncoder(androidEncoder);
      this.model.getMediaRecorder().setAudioChannels(numChannels);
      this.model.getMediaRecorder().setAudioSamplingRate(sampleRate);

      this.model.getMediaRecorder().setOutputFile(path);

      // If bitrate is defined, the use it, otherwise use the OS default
      if(bitRate != null){
        this.model.getMediaRecorder().setAudioEncodingBitRate(bitRate);
      }
    }

    try {
      this.model.getMediaRecorder().prepare();
      this.model.getMediaRecorder().start();

      // Remove all pending runnables, this is just for safety (should never happen)
      recordHandler.removeCallbacksAndMessages(null);
      final long systemTime = SystemClock.elapsedRealtime();
      this.model.setRecorderTicker(() -> {

        long time = SystemClock.elapsedRealtime() - systemTime;
//          Log.d(TAG, "elapsedTime: " + SystemClock.elapsedRealtime());
//          Log.d(TAG, "time: " + time);

//          DateFormat format = new SimpleDateFormat("mm:ss:SS", Locale.US);
//          String displayTime = format.format(time);
//          model.setRecordTime(time);
        try {
          JSONObject json = new JSONObject();
          json.put("current_position", String.valueOf(time));
          channel.invokeMethod("updateRecorderProgress", json.toString());
          recordHandler.postDelayed(model.getRecorderTicker(), model.subsDurationMillis);
        } catch (JSONException je) {
          Log.d(TAG, "Json Exception: " + je.toString());
        }
      });
      recordHandler.post(this.model.getRecorderTicker());

      if(this.model.shouldProcessDbLevel) {
        dbPeakLevelHandler.removeCallbacksAndMessages(null);
        this.model.setDbLevelTicker(() -> {

          MediaRecorder recorder = model.getMediaRecorder();
          if (recorder != null) {
            double maxAmplitude = recorder.getMaxAmplitude();

            // Calculate db based on the following article.
            // https://stackoverflow.com/questions/10655703/what-does-androids-getmaxamplitude-function-for-the-mediarecorder-actually-gi
            //
            double ref_pressure = 51805.5336;
            double p = maxAmplitude  / ref_pressure;
            double p0 = 0.0002;

            double db = 20.0 * Math.log10(p / p0);

            // if the microphone is off we get 0 for the amplitude which causes
            // db to be infinite.
            if (Double.isInfinite(db))
              db = 0.0;

            Log.d(TAG, "rawAmplitude: " + maxAmplitude + " Base DB: " + db);

            channel.invokeMethod("updateDbPeakProgress", db);
            dbPeakLevelHandler.postDelayed(model.getDbLevelTicker(),
            (FlutterSoundPlugin.this.model.peakLevelUpdateMillis));
          }
        });
        dbPeakLevelHandler.post(this.model.getDbLevelTicker());
      }


      String finalPath = path;
      mainHandler.post(new Runnable() {
        @Override
        public void run() {
          result.success(finalPath);
        }
      });
    } catch (Exception e) {
      Log.e(TAG, "Start Recorder Exception: ", e);
      if (this.model.getMediaRecorder() != null) {
        try {
          this.model.getMediaRecorder().stop();
        } catch (Exception ex1) {
          Log.e(TAG, "Stop Recorder Exception: ", ex1);
        }
        try {
          this.model.getMediaRecorder().reset();
        } catch (Exception ex2) {
          Log.e(TAG, "Reset Recorder Exception: ", ex2);
        }
        try {
          this.model.getMediaRecorder().release();
        } catch (Exception ex3) {
          Log.e(TAG, "Release Recorder Exception: ", ex3);
        }
        this.model.setMediaRecorder(null);
      }
      mainHandler.post(new Runnable() {
        @Override
        public void run() {
          result.error(ERR_UNKNOWN, ERR_UNKNOWN, e.getMessage());
        }
      });
    }
  }

  @Override
  public void stopRecorder(final Result result) {
    // This remove all pending runnables
    recordHandler.removeCallbacksAndMessages(null);
    dbPeakLevelHandler.removeCallbacksAndMessages(null);

    if (this.model.getMediaRecorder() == null) {
      Log.d(TAG, "mediaRecorder is null");
      result.error(ERR_RECORDER_IS_NULL, ERR_RECORDER_IS_NULL, ERR_RECORDER_IS_NULL);
      return;
    }
    try {
      this.model.getMediaRecorder().stop();
      this.model.getMediaRecorder().reset();
      this.model.getMediaRecorder().release();
      this.model.setMediaRecorder(null);
      mainHandler.post(new Runnable(){
        @Override
        public void run() {
          result.success("recorder stopped.");
        }
      });
    } catch (Exception e) {
      Log.e(TAG, "Stop Recorder Exception: ", e);
      this.model.setMediaRecorder(null);
      mainHandler.post(new Runnable() {
        @Override
        public void run() {
          result.error(ERR_UNKNOWN, ERR_UNKNOWN, e.getMessage());
        }
      });
    }
  }

  @Override
  public void startPlayer(final String path, final Result result) {
    if (this.model.getMediaPlayer() != null) {
      Boolean isPaused = !this.model.getMediaPlayer().isPlaying()
          && this.model.getMediaPlayer().getCurrentPosition() > 1;

      if (isPaused) {
        this.model.getMediaPlayer().start();
        result.success("player resumed.");
        return;
      }

      Log.e(TAG, "Player is already running. Stop it first.");
      result.success("player is already running.");
      return;
    } else {
      this.model.setMediaPlayer(new MediaPlayer());
    }
    mTimer = new Timer();

    try {
      if (path == null) {
        this.model.getMediaPlayer().setDataSource(AudioModel.DEFAULT_FILE_LOCATION);
      } else {
        this.model.getMediaPlayer().setDataSource(path);
      }

      this.model.getMediaPlayer().setOnPreparedListener(mp -> {
        Log.d(TAG, "mediaPlayer prepared and start");
        mp.start();

        /*
         * Set timer task to send event to RN.
         */
        TimerTask mTask = new TimerTask() {
          @Override
          public void run() {
            // long time = mp.getCurrentPosition();
            // DateFormat format = new SimpleDateFormat("mm:ss:SS", Locale.US);
            // final String displayTime = format.format(time);
            try {
              JSONObject json = new JSONObject();
              json.put("duration", String.valueOf(mp.getDuration()));
              json.put("current_position", String.valueOf(mp.getCurrentPosition()));
              mainHandler.post(new Runnable() {
                @Override
                public void run() {
                  channel.invokeMethod("updateProgress", json.toString());
                }
              });

            } catch (JSONException je) {
              Log.d(TAG, "Json Exception: " + je.toString());
            }
          }
        };

        mTimer.schedule(mTask, 0, model.subsDurationMillis);
        String resolvedPath = path == null ? AudioModel.DEFAULT_FILE_LOCATION : path;
        result.success((resolvedPath));
      });
      /*
       * Detect when finish playing.
       */
      this.model.getMediaPlayer().setOnCompletionListener(mp -> {
        /*
         * Reset player.
         */
        Log.d(TAG, "Plays completed.");
        try {
          JSONObject json = new JSONObject();
          json.put("duration", String.valueOf(mp.getDuration()));
          json.put("current_position", String.valueOf(mp.getCurrentPosition()));
          channel.invokeMethod("audioPlayerDidFinishPlaying", json.toString());
        } catch (JSONException je) {
          Log.d(TAG, "Json Exception: " + je.toString());
        }
        mTimer.cancel();
        if(mp.isPlaying())
        {
          mp.stop();
        }
        mp.reset();
        mp.release();
        model.setMediaPlayer(null);
      });
      this.model.getMediaPlayer().prepare();
    } catch (Exception e) {
      Log.e(TAG, "startPlayer() exception");
      result.error(ERR_UNKNOWN, ERR_UNKNOWN, e.getMessage());
    }
  }

  @Override
  public void stopPlayer(final Result result) {
    mTimer.cancel();

    if (this.model.getMediaPlayer() == null) {
      result.error(ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL);
      return;
    }

    try {
      this.model.getMediaPlayer().stop();
      this.model.getMediaPlayer().reset();
      this.model.getMediaPlayer().release();
      this.model.setMediaPlayer(null);
      result.success("stopped player.");
    } catch (Exception e) {
      Log.e(TAG, "stopPlay exception: " + e.getMessage());
      result.error(ERR_UNKNOWN, ERR_UNKNOWN, e.getMessage());
    }
  }

  @Override
  public void pausePlayer(final Result result) {
    if (this.model.getMediaPlayer() == null) {
      result.error(ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL);
      return;
    }

    try {
      this.model.getMediaPlayer().pause();
      result.success("paused player.");
    } catch (Exception e) {
      Log.e(TAG, "pausePlay exception: " + e.getMessage());
      result.error(ERR_UNKNOWN, ERR_UNKNOWN, e.getMessage());
    }
  }

  @Override
  public void resumePlayer(final Result result) {
    if (this.model.getMediaPlayer() == null) {
      result.error(ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL);
      return;
    }

    if (this.model.getMediaPlayer().isPlaying()) {
      result.error(ERR_PLAYER_IS_PLAYING, ERR_PLAYER_IS_PLAYING, ERR_PLAYER_IS_PLAYING);
      return;
    }

    try {
      this.model.getMediaPlayer().seekTo(this.model.getMediaPlayer().getCurrentPosition());
      this.model.getMediaPlayer().start();
      result.success("resumed player.");
    } catch (Exception e) {
      Log.e(TAG, "mediaPlayer resume: " + e.getMessage());
      result.error(ERR_UNKNOWN, ERR_UNKNOWN, e.getMessage());
    }
  }

  @Override
  public void seekToPlayer(int millis, final Result result) {
    if (this.model.getMediaPlayer() == null) {
      result.error(ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL);
      return;
    }

    int currentMillis = this.model.getMediaPlayer().getCurrentPosition();
    Log.d(TAG, "currentMillis: " + currentMillis);
    // millis += currentMillis; [This was the problem for me]

    Log.d(TAG, "seekTo: " + millis);

    this.model.getMediaPlayer().seekTo(millis);
    result.success(String.valueOf(millis));
  }

  @Override
  public void setVolume(double volume, final Result result) {
    if (this.model.getMediaPlayer() == null) {
      result.error(ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL, ERR_PLAYER_IS_NULL);
      return;
    }

    float mVolume = (float) volume;
    this.model.getMediaPlayer().setVolume(mVolume, mVolume);
    result.success("Set volume");
  }

  @Override
  public void setDbPeakLevelUpdate(double intervalInSecs, Result result) {
    this.model.peakLevelUpdateMillis = (long) (intervalInSecs * 1000);
    result.success("setDbPeakLevelUpdate: " + this.model.peakLevelUpdateMillis);
  }

  @Override
  public void setDbLevelEnabled(boolean enabled, MethodChannel.Result result) {
    this.model.shouldProcessDbLevel = enabled;
    result.success("setDbLevelEnabled: " + this.model.shouldProcessDbLevel);
  }

  @Override
  public void setSubscriptionDuration(double sec, Result result) {
    this.model.subsDurationMillis = (int) (sec * 1000);
    result.success("setSubscriptionDuration: " + this.model.subsDurationMillis);
  }
}
