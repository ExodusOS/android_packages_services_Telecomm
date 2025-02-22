/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom;

import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.AsyncTask;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.LogUtils.EventTimer;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Controls the ringtone player.
 */
@VisibleForTesting
public class Ringer {
    public static class VibrationEffectProxy {
        public VibrationEffect createWaveform(long[] timings, int[] amplitudes, int repeat) {
            return VibrationEffect.createWaveform(timings, amplitudes, repeat);
        }

        public VibrationEffect get(Uri ringtoneUri, Context context) {
            return VibrationEffect.get(ringtoneUri, context);
        }
    }
    @VisibleForTesting
    public VibrationEffect mDefaultVibrationEffect;

    private static final long[] PULSE_PRIMING_PATTERN = {0,12,250,12,500}; // priming  + interval

    private static final int[] PULSE_PRIMING_AMPLITUDE = {0,255,0,255,0};  // priming  + interval

    // ease-in + peak + pause
    private static final long[] PULSE_RAMPING_PATTERN = {
        50,50,50,50,50,50,50,50,50,50,50,50,50,50,300,1000};

    // ease-in (min amplitude = 30%) + peak + pause
    private static final int[] PULSE_RAMPING_AMPLITUDE = {
        77,77,78,79,81,84,87,93,101,114,133,162,205,255,255,0};

    private static final long[] PULSE_PATTERN;

    private static final int[] PULSE_AMPLITUDE;

    private static final int RAMPING_RINGER_VIBRATION_DURATION = 5000;
    private static final int RAMPING_RINGER_DURATION = 10000;

    private int mRampingRingerDuration = -1;  // ramping ringer duration in millisecond
    private float mRampingRingerStartVolume = 0f;

    static {
        // construct complete pulse pattern
        PULSE_PATTERN = new long[PULSE_PRIMING_PATTERN.length + PULSE_RAMPING_PATTERN.length];
        System.arraycopy(
            PULSE_PRIMING_PATTERN, 0, PULSE_PATTERN, 0, PULSE_PRIMING_PATTERN.length);
        System.arraycopy(PULSE_RAMPING_PATTERN, 0, PULSE_PATTERN,
            PULSE_PRIMING_PATTERN.length, PULSE_RAMPING_PATTERN.length);

        // construct complete pulse amplitude
        PULSE_AMPLITUDE = new int[PULSE_PRIMING_AMPLITUDE.length + PULSE_RAMPING_AMPLITUDE.length];
        System.arraycopy(
            PULSE_PRIMING_AMPLITUDE, 0, PULSE_AMPLITUDE, 0, PULSE_PRIMING_AMPLITUDE.length);
        System.arraycopy(PULSE_RAMPING_AMPLITUDE, 0, PULSE_AMPLITUDE,
            PULSE_PRIMING_AMPLITUDE.length, PULSE_RAMPING_AMPLITUDE.length);
    }

    private static final long[] SIMPLE_VIBRATION_PATTERN = {
            0, // No delay before starting
            1000, // How long to vibrate
            1000, // How long to wait before vibrating again
    };

    private static final int[] SIMPLE_VIBRATION_AMPLITUDE = {
            0, // No delay before starting
            255, // Vibrate full amplitude
            0, // No amplitude while waiting
    };

    /**
     * Indicates that vibration should be repeated at element 5 in the {@link #PULSE_AMPLITUDE} and
     * {@link #PULSE_PATTERN} arrays.  This means repetition will happen for the main ease-in/peak
     * pattern, but the priming + interval part will not be repeated.
     */
    private static final int REPEAT_VIBRATION_AT = 5;

    private static final int REPEAT_SIMPLE_VIBRATION_AT = 1;

    private static final long RINGER_ATTRIBUTES_TIMEOUT = 5000; // 5 seconds

    private static final float EPSILON = 1e-6f;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();

    private static VibrationEffect mRampingRingerVibrationEffect;
    private static VolumeShaper.Configuration mVolumeShaperConfig;

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */

    private final SystemSettingsUtil mSystemSettingsUtil;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final AsyncRingtonePlayer mRingtonePlayer;
    private final Context mContext;
    private final Vibrator mVibrator;
    private final InCallController mInCallController;
    private final VibrationEffectProxy mVibrationEffectProxy;
    private final boolean mIsHapticPlaybackSupportedByDevice;
    /**
     * For unit testing purposes only; when set, {@link #startRinging(Call, boolean)} will complete
     * the future provided by the test using {@link #setBlockOnRingingFuture(CompletableFuture)}.
     */
    private CompletableFuture<Void> mBlockOnRingingFuture = null;

    private CompletableFuture<Void> mVibrateFuture = CompletableFuture.completedFuture(null);

    private TorchToggler torchToggler;

    private InCallTonePlayer mCallWaitingPlayer;
    private RingtoneFactory mRingtoneFactory;
    private AudioManager mAudioManager;

    /**
     * Call objects that are ringing, vibrating or call-waiting. These are used only for logging
     * purposes.
     */
    private Call mRingingCall;
    private Call mVibratingCall;
    private Call mCallWaitingCall;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private boolean mIsVibrating = false;

    private Handler mHandler = null;
    private int torchMode;

    private boolean mBlinkActive;

    /** Initializes the Ringer. */
    @VisibleForTesting
    public Ringer(
            InCallTonePlayer.Factory playerFactory,
            Context context,
            SystemSettingsUtil systemSettingsUtil,
            AsyncRingtonePlayer asyncRingtonePlayer,
            RingtoneFactory ringtoneFactory,
            Vibrator vibrator,
            VibrationEffectProxy vibrationEffectProxy,
            InCallController inCallController) {

        mSystemSettingsUtil = systemSettingsUtil;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = vibrator;
        mRingtonePlayer = asyncRingtonePlayer;
        mRingtoneFactory = ringtoneFactory;
        mInCallController = inCallController;
        mVibrationEffectProxy = vibrationEffectProxy;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        torchToggler = new TorchToggler(context);

        if (mContext.getResources().getBoolean(R.bool.use_simple_vibration_pattern)) {
            mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(SIMPLE_VIBRATION_PATTERN,
                    SIMPLE_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
        } else {
            mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(PULSE_PATTERN,
                    PULSE_AMPLITUDE, REPEAT_VIBRATION_AT);
        }

        mIsHapticPlaybackSupportedByDevice =
                mSystemSettingsUtil.isHapticPlaybackSupported(mContext);
    }

    @VisibleForTesting
    public void setBlockOnRingingFuture(CompletableFuture<Void> future) {
        mBlockOnRingingFuture = future;
    }

    public boolean startRinging(Call foregroundCall, boolean isHfpDeviceAttached) {
        if (foregroundCall == null) {
            Log.wtf(this, "startRinging called with null foreground call.");
            return false;
        }

        if (foregroundCall.getState() != CallState.RINGING
                && foregroundCall.getState() != CallState.SIMULATED_RINGING) {
            // Its possible for bluetooth to connect JUST as a call goes active, which would mean
            // the call would start ringing again.
            Log.i(this, "startRinging called for non-ringing foreground callid=%s",
                    foregroundCall.getId());
            return false;
        }

        // Use completable future to establish a timeout, not intent to make these work outside the
        // main thread asynchronously
        // TODO: moving these RingerAttributes calculation out of Telecom lock to avoid blocking.
        CompletableFuture<RingerAttributes> ringerAttributesFuture = CompletableFuture
                .supplyAsync(() -> getRingerAttributes(foregroundCall, isHfpDeviceAttached),
                        new LoggedHandlerExecutor(getHandler(), "R.sR", null));

        RingerAttributes attributes = null;
        try {
            attributes = ringerAttributesFuture.get(
                    RINGER_ATTRIBUTES_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // Keep attributs as null
            Log.i(this, "getAttributes error: " + e);
        }

        if (attributes == null) {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "RingerAttributes error");
            return false;
        }

        if (attributes.isEndEarly()) {
            if (attributes.letDialerHandleRinging()) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            }
            if (attributes.isSilentRingingRequested()) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Silent ringing "
                        + "requested");
            }
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            return attributes.shouldAcquireAudioFocus();
        }

        stopCallWaiting();

        VibrationEffect effect;
        CompletableFuture<Boolean> hapticsFuture = null;
        // Determine if the settings and DND mode indicate that the vibrator can be used right now.
        boolean isVibratorEnabled = isVibratorEnabled(mContext, foregroundCall);
        if (attributes.isRingerAudible()) {
            mRingingCall = foregroundCall;
            Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);
            // Because we wait until a contact info query to complete before processing a
            // call (for the purposes of direct-to-voicemail), the information about custom
            // ringtones should be available by the time this code executes. We can safely
            // request the custom ringtone from the call and expect it to be current.
            if (mSystemSettingsUtil.applyRampingRinger(mContext)) {
                Log.i(this, "start ramping ringer.");
                if (mSystemSettingsUtil.enableAudioCoupledVibrationForRampingRinger()) {
                    effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
                } else {
                    effect = mDefaultVibrationEffect;
                }
                if (mVolumeShaperConfig == null) {
                    float silencePoint = (float) (RAMPING_RINGER_VIBRATION_DURATION)
                            / (float) (RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION);
                    mVolumeShaperConfig = new VolumeShaper.Configuration.Builder()
                            .setDuration(
                                    RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION)
                            .setCurve(new float[]{0.f, silencePoint + EPSILON /*keep monotonicity*/,
                                    1.f}, new float[]{0.f, 0.f, 1.f})
                            .setInterpolatorType(
                                    VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                            .build();
                }
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall,
                        mVolumeShaperConfig, attributes.isRingerAudible(), isVibratorEnabled);
            } else {
                final ContentResolver cr = mContext.getContentResolver();
                if (LineageSettings.System.getInt(cr,
                        LineageSettings.System.INCREASING_RING, 0) != 0) {
                    float startVolume = LineageSettings.System.getFloat(cr,
                            LineageSettings.System.INCREASING_RING_START_VOLUME, 0.1f);
                    int rampUpTime = LineageSettings.System.getInt(cr,
                            LineageSettings.System.INCREASING_RING_RAMP_UP_TIME, 20);
                    if (mVolumeShaperConfig == null
                        || mRampingRingerDuration != rampUpTime
                        || mRampingRingerStartVolume != startVolume) {
                        mVolumeShaperConfig = new VolumeShaper.Configuration.Builder()
                            .setDuration(rampUpTime * 1000)
                            .setCurve(new float[] {0.f, 1.f}, new float[] {startVolume, 1.f})
                            .setInterpolatorType(VolumeShaper
                                    .Configuration.INTERPOLATOR_TYPE_LINEAR)
                            .build();
                        mRampingRingerDuration = rampUpTime;
                        mRampingRingerStartVolume = startVolume;
                    }
                } else {
                    mVolumeShaperConfig = null;
                }
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall,
                        mVolumeShaperConfig, attributes.isRingerAudible(), isVibratorEnabled);
                effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
            }
        } else {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Inaudible: "
                    + attributes.getInaudibleReason());
            if (isVibratorEnabled && mIsHapticPlaybackSupportedByDevice) {
                // Attempt to run the attentional haptic ringtone first and fallback to the default
                // vibration effect if hapticFuture is completed with false.
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall, null,
                        attributes.isRingerAudible(), isVibratorEnabled);
            }
            effect = mDefaultVibrationEffect;
        }

        torchMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.FLASHLIGHT_ON_CALL, 0, UserHandle.USER_CURRENT);
        boolean shouldFlash = false;
        if (torchMode != 0) {
            switch (torchMode) {
                case 1: // Flash when ringer is audible
                    shouldFlash = attributes.isRingerAudible();
                    break;
                case 2: // Flash when ringer is not audible
                    shouldFlash = !attributes.isRingerAudible();
                    break;
                case 3: // Flash when entirely silent (no vibration or sound)
                    shouldFlash = !isVibratorEnabled && !attributes.isRingerAudible();
                    break;
                case 4: // Flash always
                    shouldFlash = true;
                    break;
            }
        }

        boolean ignoreDND = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.FLASHLIGHT_ON_CALL_IGNORE_DND, 0,
                UserHandle.USER_CURRENT) == 1;
        if (!ignoreDND && shouldFlash) { // respect DND
            int zenMode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
            shouldFlash = zenMode == Settings.Global.ZEN_MODE_OFF;
        }

        if (shouldFlash && !mBlinkActive) {
            mBlinkActive = true;
            blinkFlashlight();
        }

        if (hapticsFuture != null) {
            final boolean shouldRingForContact = attributes.shouldRingForContact();
            final boolean isRingerAudible = attributes.isRingerAudible();
            mVibrateFuture = hapticsFuture.thenAccept(isUsingAudioCoupledHaptics -> {
                if (!isUsingAudioCoupledHaptics || !mIsHapticPlaybackSupportedByDevice) {
                    Log.i(this, "startRinging: fileHasHaptics=%b, hapticsSupported=%b",
                            isUsingAudioCoupledHaptics, mIsHapticPlaybackSupportedByDevice);
                    maybeStartVibration(foregroundCall, shouldRingForContact, effect,
                            isVibratorEnabled, isRingerAudible);
                } else if (mSystemSettingsUtil.applyRampingRinger(mContext)
                           && !mSystemSettingsUtil.enableAudioCoupledVibrationForRampingRinger()) {
                    Log.i(this, "startRinging: apply ramping ringer vibration");
                    maybeStartVibration(foregroundCall, shouldRingForContact, effect,
                            isVibratorEnabled, isRingerAudible);
                } else {
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION,
                            "using audio-coupled haptics");
                }
            });
            if (mBlockOnRingingFuture != null) {
                mVibrateFuture.whenComplete((v, e) -> mBlockOnRingingFuture.complete(null));
            }
        } else {
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            Log.w(this, "startRinging: No haptics future; fallback to default behavior");
            maybeStartVibration(foregroundCall, attributes.shouldRingForContact(), effect,
                    isVibratorEnabled, attributes.isRingerAudible());
        }

        return attributes.shouldAcquireAudioFocus();
    }

    private void blinkFlashlight() {
        torchToggler = new TorchToggler(mContext);
        torchToggler.execute();
    }

    private void maybeStartVibration(Call foregroundCall, boolean shouldRingForContact,
        VibrationEffect effect, boolean isVibrationEnabled, boolean isRingerAudible) {
        if (isVibrationEnabled
                && !mIsVibrating && shouldRingForContact) {
            if (mSystemSettingsUtil.applyRampingRinger(mContext)
                    && isRingerAudible) {
                Log.i(this, "start vibration for ramping ringer.");
                mIsVibrating = true;
                mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
            } else {
                Log.i(this, "start normal vibration.");
                mIsVibrating = true;
                mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
            }
        } else if (mIsVibrating) {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION, "already vibrating");
        }
    }

    private VibrationEffect getVibrationEffectForCall(RingtoneFactory factory, Call call) {
        VibrationEffect effect = null;
        Ringtone ringtone = factory.getRingtone(call);
        Uri ringtoneUri = ringtone != null ? ringtone.getUri() : null;
        if (ringtoneUri != null) {
            try {
                effect = mVibrationEffectProxy.get(ringtoneUri, mContext);
            } catch (IllegalArgumentException iae) {
                // Deep in the bowels of the VibrationEffect class it is possible for an
                // IllegalArgumentException to be thrown if there is an invalid URI specified in the
                // device config, or a content provider failure.  Rather than crashing the Telecom
                // process we will just use the default vibration effect.
                Log.e(this, iae, "getVibrationEffectForCall: failed to get vibration effect");
                effect = null;
            }
        }

        if (effect == null) {
            effect = mDefaultVibrationEffect;
        }
        return effect;
    }

    public void startCallWaiting(Call call) {
        startCallWaiting(call, null);
    }

    public void startCallWaiting(Call call, String reason) {
        mBlinkActive = false;

        if (mSystemSettingsUtil.isTheaterModeOn(mContext)) {
            return;
        }

        if (mInCallController.doesConnectedDialerSupportRinging()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            return;
        }

        if (call.isSelfManaged()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Self-managed");
            return;
        }

        Log.v(this, "Playing call-waiting tone.");

        stopRinging();

        if (mCallWaitingPlayer == null) {
            Log.addEvent(call, LogUtils.Events.START_CALL_WAITING_TONE, reason);
            mCallWaitingCall = call;
            mCallWaitingPlayer =
                    mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
            mCallWaitingPlayer.startTone();
        }
    }

    public void stopRinging() {
        if (mRingingCall != null) {
            Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
            mRingingCall = null;
        }

        mBlinkActive = false;
        mRingtonePlayer.stop();

        // If we haven't started vibrating because we were waiting for the haptics info, cancel
        // it and don't vibrate at all.
        if (mVibrateFuture != null) {
            mVibrateFuture.cancel(true);
        }

        if (mIsVibrating) {
            Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
            mVibrator.cancel();
            mIsVibrating = false;
            mVibratingCall = null;
        }
    }

    public void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            if (mCallWaitingCall != null) {
                Log.addEvent(mCallWaitingCall, LogUtils.Events.STOP_CALL_WAITING_TONE);
                mCallWaitingCall = null;
            }

            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    public boolean isRinging() {
        return mRingtonePlayer.isPlaying();
    }

    private boolean shouldRingForContact(Uri contactUri) {
        final NotificationManager manager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        final Bundle peopleExtras = new Bundle();
        if (contactUri != null) {
            ArrayList<Person> personList = new ArrayList<>();
            personList.add(new Person.Builder().setUri(contactUri.toString()).build());
            peopleExtras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, personList);
        }
        return manager.matchesCallFilter(peopleExtras);
    }

    private boolean hasExternalRinger(Call foregroundCall) {
        Bundle intentExtras = foregroundCall.getIntentExtras();
        if (intentExtras != null) {
            return intentExtras.getBoolean(TelecomManager.EXTRA_CALL_EXTERNAL_RINGER, false);
        } else {
            return false;
        }
    }

    private boolean isVibratorEnabled(Context context, Call call) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerModeInternal();
        boolean shouldVibrate;
        if (getVibrateWhenRinging(context)) {
            shouldVibrate = ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            shouldVibrate = ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }

        // Technically this should be in the calling method, but it seemed a little odd to pass
        // around a whole bunch of state just for logging purposes.
        if (shouldVibrate) {
            Log.addEvent(call, LogUtils.Events.START_VIBRATOR,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                    mVibrator.hasVibrator(), mSystemSettingsUtil.canVibrateWhenRinging(context),
                    ringerMode, mIsVibrating);
        } else {
            Log.addEvent(call, LogUtils.Events.SKIP_VIBRATION,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                    mVibrator.hasVibrator(), mSystemSettingsUtil.canVibrateWhenRinging(context),
                    ringerMode, mIsVibrating);
        }

        return shouldVibrate;
    }

    private boolean getVibrateWhenRinging(Context context) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        return mSystemSettingsUtil.canVibrateWhenRinging(context)
            || mSystemSettingsUtil.applyRampingRinger(context);
    }

    private RingerAttributes getRingerAttributes(Call call, boolean isHfpDeviceAttached) {
        RingerAttributes.Builder builder = new RingerAttributes.Builder();

        LogUtils.EventTimer timer = new EventTimer();

        boolean isVolumeOverZero = mAudioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;
        timer.record("isVolumeOverZero");
        boolean shouldRingForContact = shouldRingForContact(call.getHandle());
        timer.record("shouldRingForContact");
        boolean isRingtonePresent = !(mRingtoneFactory.getRingtone(call) == null);
        timer.record("getRingtone");
        boolean isSelfManaged = call.isSelfManaged();
        timer.record("isSelfManaged");
        boolean isSilentRingingRequested = call.isSilentRingingRequested();
        timer.record("isSilentRingRequested");

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact && isRingtonePresent;
        timer.record("isRingerAudible");
        String inaudibleReason = "";
        if (!isRingerAudible) {
            inaudibleReason = String.format(
                    "isVolumeOverZero=%s, shouldRingForContact=%s, isRingtonePresent=%s",
                    isVolumeOverZero, shouldRingForContact, isRingtonePresent);
        }

        boolean hasExternalRinger = hasExternalRinger(call);
        timer.record("hasExternalRinger");
        // Don't do call waiting operations or vibration unless these are false.
        boolean isTheaterModeOn = mSystemSettingsUtil.isTheaterModeOn(mContext);
        timer.record("isTheaterModeOn");
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging();
        timer.record("letDialerHandleRinging");

        Log.i(this, "startRinging timings: " + timer);
        boolean endEarly = isTheaterModeOn || letDialerHandleRinging || isSelfManaged ||
                hasExternalRinger || isSilentRingingRequested;

        if (endEarly) {
            Log.i(this, "Ending early -- isTheaterModeOn=%s, letDialerHandleRinging=%s, " +
                            "isSelfManaged=%s, hasExternalRinger=%s, silentRingingRequested=%s",
                    isTheaterModeOn, letDialerHandleRinging, isSelfManaged, hasExternalRinger,
                    isSilentRingingRequested);
        }

        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present.
        // 3. The call is self-managed.
        boolean shouldAcquireAudioFocus =
                isRingerAudible || (isHfpDeviceAttached && shouldRingForContact) || isSelfManaged;

        return builder.setEndEarly(endEarly)
                .setLetDialerHandleRinging(letDialerHandleRinging)
                .setAcquireAudioFocus(shouldAcquireAudioFocus)
                .setRingerAudible(isRingerAudible)
                .setInaudibleReason(inaudibleReason)
                .setShouldRingForContact(shouldRingForContact)
                .setSilentRingingRequested(isSilentRingingRequested)
                .build();
    }

    private Handler getHandler() {
        if (mHandler == null) {
            HandlerThread handlerThread = new HandlerThread("Ringer");
            handlerThread.start();
            mHandler = handlerThread.getThreadHandler();
        }
        return mHandler;
    }

    private class TorchToggler extends AsyncTask {

        private CameraManager cameraManager;
        private int duration;
        private boolean hasFlash = true;
        private Context context;

        public TorchToggler(Context ctx) {
            this.context = ctx;
            init();
        }

        private void init() {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            hasFlash = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
            duration = 500 / Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.FLASHLIGHT_ON_CALL_RATE, 1, UserHandle.USER_CURRENT);
        }

        @Override
        protected Object doInBackground(Object[] objects) {
            if (hasFlash) {
                try {
                    String cameraId = cameraManager.getCameraIdList()[0];
                    while (mBlinkActive) {
                        cameraManager.setTorchMode(cameraId, true);
                        Thread.sleep(duration);

                        cameraManager.setTorchMode(cameraId, false);
                        Thread.sleep(duration);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
    }
}
