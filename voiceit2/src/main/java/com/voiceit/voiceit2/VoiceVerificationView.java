package com.voiceit.voiceit2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.io.File;

import com.loopj.android.http.JsonHttpResponseHandler;
import cz.msebera.android.httpclient.Header;

import org.json.JSONException;
import org.json.JSONObject;

public class VoiceVerificationView extends AppCompatActivity {

    final int PERMISSIONS_REQUEST_RECORD_AUDIO = 0;
    final int ASK_MULTIPLE_PERMISSION_REQUEST_CODE = 1;

    private final String mTAG = "VoiceVerificationView";
    private Context mContext;

    private RadiusOverlayView mOverlay;
    private MediaRecorder mMediaRecorder = null;

    private VoiceItAPI2 mVoiceIt2;
    private String mUserID = "";
    private String mContentLanguage = "";
    private String mPhrase = "";

    private final int mNeededEnrollments = 3;
    private int mFailedAttempts = 0;
    private final int mMaxFailedAttempts = 3;
    private boolean mContinueVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);

        super.onCreate(savedInstanceState);

        // Grab data from parent activity
        Bundle bundle = getIntent().getExtras();
        if(bundle != null) {
            mVoiceIt2 = new VoiceItAPI2(bundle.getString("apiKey"), bundle.getString("apiToken"));
            mUserID = bundle.getString("userID");
            mContentLanguage = bundle.getString("contentLanguage");
            mPhrase = bundle.getString("phrase");
        }

        // Hide action bar
        try {
            this.getSupportActionBar().hide();
        } catch (NullPointerException e) {
            Log.d(mTAG,"Cannot hide action bar");
        }

        // Set context
        mContext = this;
        // Set content view
        setContentView(R.layout.activity_voice_verification_view);

        // Orient screen
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Text output on mOverlay
        mOverlay = findViewById(R.id.overlay);
    }

    private void requestHardwarePermissions() {
        // MY_PERMISSIONS_REQUEST_* is an app-defined int constant. The callback method gets the
        // result of the request.
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
                requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO},
                        ASK_MULTIPLE_PERMISSION_REQUEST_CODE);
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                            PERMISSIONS_REQUEST_RECORD_AUDIO);
                }
            }
        } else {
            // Permissions granted, so continue with view
            verifyUser();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(mTAG,"Hardware Permissions not granted");
            exitViewWithMessage("voiceit-failure", "Hardware Permissions not granted");
        } else {
            // Permissions granted, so continue with view
            verifyUser();
        }
    }

    private void exitViewWithMessage(String action, String message) {
        mContinueVerifying = false;
        stopRecording();
        Intent intent = new Intent(action);
        JSONObject json = new JSONObject();
        try {
            json.put("message", message);
        } catch(JSONException e) {
            Log.d(mTAG,"JSON Exception : " + e.getMessage());
        }
        intent.putExtra("Response", json.toString());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        finish();
    }

    private void exitViewWithJSON(String action, JSONObject json) {
        mContinueVerifying = false;
        stopRecording();
        Intent intent = new Intent(action);
        intent.putExtra("Response", json.toString());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        exitViewWithMessage("voiceit-failure", "User Canceled");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Confirm permissions and start verification flow
        requestHardwarePermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Confirm permissions and start enrollment flow
        requestHardwarePermissions();
    }

    private void stopRecording() {
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (Exception e) {
                Log.d(mTAG, "Error trying to stop MediaRecorder");
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    private void failVerification(final JSONObject response) {
        mOverlay.setProgressCircleColor(getResources().getColor(R.color.red));
        mOverlay.updateDisplayText(getString(R.string.VERIFY_FAIL));

        // Wait for ~1.5 seconds
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // Report error to user
                    mOverlay.updateDisplayText(getString((getResources().getIdentifier(response.
                            getString("responseCode"), "string", getPackageName()))));
                } catch (JSONException e) {
                    Log.d(mTAG,"JSON exception : " + e.toString());
                }
                // Wait for ~4.5 seconds
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (response.getString("responseCode").equals("PNTE")) {
                                exitViewWithJSON("voiceit-failure", response);
                            }
                        } catch (JSONException e) {
                            Log.d(mTAG,"JSON exception : " + e.toString());
                        }

                        mFailedAttempts++;
                        // User failed too many times
                        if (mFailedAttempts >= mMaxFailedAttempts) {
                            mOverlay.updateDisplayText(getString(R.string.TOO_MANY_ATTEMPTS));
                            // Wait for ~2 seconds then exit
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    exitViewWithJSON("voiceit-failure", response);
                                }
                            }, 2000);
                        } else if (mContinueVerifying) {
                            // Try again
                            recordVoice();
                        }
                    }
                }, 4500);
            }
        }, 1500);
    }

    // Verify after recording voice
    private void recordVoice() {
        try {
            // Create file for audio
            final File audioFile = Utils.getOutputMediaFile(".wav");

            // Setup device and capture Audio
            mMediaRecorder = new MediaRecorder();
            Utils.startMediaRecorder(mMediaRecorder, audioFile);

            // Refresh amplitude
            mMediaRecorder.getMaxAmplitude();
            // Make waveform move to show user recording has started
            mOverlay.setSineWaveAmpGoal(5000);

            mOverlay.updateDisplayText(getString(R.string.SAY_PASSPHRASE, mPhrase));
            // Record and update amplitude display for ~5 seconds, then send data
            // 4800 to make sure recording is not over 5 seconds
            new CountDownTimer(4800, 10) {
                public void onTick(long millisUntilFinished) {
                    if(mMediaRecorder != null) {
                        mOverlay.setSineWaveAmpGoal(mMediaRecorder.getMaxAmplitude());
                    }
                }

                public void onFinish() {
                    if (mContinueVerifying) {
                        stopRecording();

                        // Reset sine wave
                        mOverlay.setSineWaveAmpGoal(1);
                        mOverlay.updateDisplayText(getString(R.string.WAIT));
                        mVoiceIt2.voiceVerification(mUserID, mContentLanguage, audioFile, new JsonHttpResponseHandler() {
                            @Override
                            public void onSuccess(int statusCode, Header[] headers, final JSONObject response) {
                                try {
                                    // Wrong mPhrase
                                    if (!response.getString("text").toLowerCase().equals(mPhrase.toLowerCase())) {
                                        mOverlay.setProgressCircleColor(getResources().getColor(R.color.red));
                                        mOverlay.updateDisplayText(getString(R.string.VERIFY_FAIL));

                                        // Wait for ~1.5 seconds
                                        new Handler().postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                mOverlay.updateDisplayText(getString(R.string.INCORRECT_PASSPHRASE, mPhrase));
                                                // Wait for ~4.5 seconds
                                                new Handler().postDelayed(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        audioFile.deleteOnExit();
                                                        mFailedAttempts++;

                                                        // User failed too many times
                                                        if (mFailedAttempts >= mMaxFailedAttempts) {
                                                            mOverlay.updateDisplayText(getString(R.string.TOO_MANY_ATTEMPTS));
                                                            // Wait for ~2 seconds
                                                            new Handler().postDelayed(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    exitViewWithMessage("voiceit-failure", "Too many attempts");
                                                                }
                                                            }, 2000);
                                                        } else if (mContinueVerifying) {
                                                            // Try again
                                                            recordVoice();
                                                        }
                                                    }
                                                }, 4500);
                                            }
                                        }, 1500);
                                        // Success
                                    } else if (response.getString("responseCode").equals("SUCC")) {
                                        mOverlay.setProgressCircleColor(getResources().getColor(R.color.green));
                                        mOverlay.updateDisplayTextAndLock(getString(R.string.VERIFY_SUCCESS));

                                        // Wait for ~2 seconds then exit
                                        new Handler().postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                audioFile.deleteOnExit();
                                                exitViewWithJSON("voiceit-success", response);
                                            }
                                        }, 2000);
                                        // Fail
                                    } else {
                                        audioFile.deleteOnExit();
                                        failVerification(response);
                                    }
                                } catch (JSONException e) {
                                    Log.d(mTAG, "JSON Exception: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onFailure(int statusCode, Header[] headers, Throwable throwable, final JSONObject errorResponse) {
                                if (errorResponse != null) {
                                    Log.d(mTAG, "JSONResult : " + errorResponse.toString());

                                    audioFile.deleteOnExit();
                                    failVerification(errorResponse);
                                } else {
                                    Log.e(mTAG, "No response from server");
                                    mOverlay.updateDisplayTextAndLock(getString(R.string.CHECK_INTERNET));
                                    // Wait for 2.0 seconds
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            exitViewWithMessage("voiceit-failure", "No response from server");
                                        }
                                    }, 2000);
                                }
                            }
                        });
                    }
                }
            }.start();
        } catch (Exception ex) {
            Log.d(mTAG, "Recording Error: " + ex.getMessage());
            exitViewWithMessage("voiceit-failure", "Recording Error");
        }
    }

    public void verifyUser() {
        mContinueVerifying = true;
        // Check enrollments then verify
        mVoiceIt2.getAllEnrollmentsForUser(mUserID, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, final JSONObject response) {
                try {
                    // Check If enough enrollments, otherwise return to previous activity
                    if(response.getInt("count") < mNeededEnrollments) {
                        mOverlay.updateDisplayText(getString(R.string.NOT_ENOUGH_ENROLLMENTS));
                        // Wait for ~2.5 seconds
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                exitViewWithMessage("voiceit-failure", "Not enough enrollments");
                            }
                        }, 2500);
                    } else {
                        try {
                            // Wait for .5 seconds to read message
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                        // Record Voice then verify
                                        recordVoice();
                                    }
                            }, 500);
                        } catch (Exception e) {
                            Log.d(mTAG,"MediaRecorder exception : " + e.getMessage());
                            exitViewWithMessage("voiceit-failure", "MediaRecorder exception");
                        }
                    }
                } catch (JSONException e) {
                    Log.d(mTAG,"JSON userId error: " + e.getMessage());
                    exitViewWithMessage("voiceit-failure", "JSON userId error");
                }
            }
            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, final JSONObject errorResponse){
                if (errorResponse != null) {
                    try {
                        // Report error to user
                        mOverlay.updateDisplayText(getString((getResources().getIdentifier(errorResponse.
                                getString("responseCode"), "string", getPackageName()))));
                    } catch (JSONException e) {
                        Log.d(mTAG,"JSON exception : " + e.toString());
                    }
                    // Wait for 2.0 seconds
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            exitViewWithJSON("voiceit-failure", errorResponse);
                        }
                    }, 2000);
                } else {
                    Log.e(mTAG, "No response from server");
                    mOverlay.updateDisplayTextAndLock(getString(R.string.CHECK_INTERNET));
                    // Wait for 2.0 seconds
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            exitViewWithMessage("voiceit-failure", "No response from server");
                        }
                    }, 2000);
                }
            }
        });
    }
}