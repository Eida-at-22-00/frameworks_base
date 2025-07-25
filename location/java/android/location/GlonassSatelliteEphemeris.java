/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License.
 */

package android.location;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class contains ephemeris parameters specific to Glonass satellites.
 *
 * <p>This is defined in RINEX 3.05 APPENDIX 10 and Glonass ICD v5.1 section 4.4.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GlonassSatelliteEphemeris implements Parcelable {

    /**
     * Glonass signal health status.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HEALTH_STATUS_HEALTHY, HEALTH_STATUS_UNHEALTHY})
    public @interface GlonassHealthStatus {}

    /**
     * The following enumerations must be in sync with the values declared in
     * GlonassSatelliteEphemeris.aidl
     */

    /** Health status is healthy. */
    public static final int HEALTH_STATUS_HEALTHY = 0;

    /** Health status is unhealthy. */
    public static final int HEALTH_STATUS_UNHEALTHY = 1;

    /** L1/Satellite system (R), satellite number (slot number in sat. constellation). */
    private final int mSlotNumber;

    /** Health state. */
    private final @GlonassHealthStatus int mHealthState;

    /** Message frame time in seconds of the UTC week (tk+nd*86400). */
    private final double mFrameTimeSeconds;

    /** Age of current information in days (E). */
    private final int mAgeInDays;

    /** Update and validity interval in minutes (P1) */
    private final int mUpdateIntervalMinutes;

    /** Flag to indicate if the update interval is odd or even (P2). */
    private final boolean mUpdateIntervalOdd;

    /** Flag to indicates if the satellite is a Glonass-M satellitee (M). */
    private final boolean mGlonassM;

    /** Satellite clock model. */
    @NonNull private final GlonassSatelliteClockModel mSatelliteClockModel;

    /** Satellite orbit model. */
    @NonNull private final GlonassSatelliteOrbitModel mSatelliteOrbitModel;

    private GlonassSatelliteEphemeris(Builder builder) {
        // Allow SlotNumber beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mSlotNumber >= 1);
        // Allow HealthState beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mHealthState >= 0);
        Preconditions.checkArgument(builder.mFrameTimeSeconds >= 0.0f);
        Preconditions.checkArgumentInRange(builder.mAgeInDays, 0, 31, "AgeInDays");
        Preconditions.checkArgumentInRange(
                builder.mUpdateIntervalMinutes, 0, 60, "UpdateIntervalMinutes");
        Preconditions.checkNotNull(
                builder.mSatelliteClockModel, "SatelliteClockModel cannot be null");
        Preconditions.checkNotNull(
                builder.mSatelliteOrbitModel, "SatelliteOrbitModel cannot be null");
        mSlotNumber = builder.mSlotNumber;
        mHealthState = builder.mHealthState;
        mFrameTimeSeconds = builder.mFrameTimeSeconds;
        mAgeInDays = builder.mAgeInDays;
        mUpdateIntervalMinutes = builder.mUpdateIntervalMinutes;
        mUpdateIntervalOdd = builder.mUpdateIntervalOdd;
        mGlonassM = builder.mGlonassM;
        mSatelliteClockModel = builder.mSatelliteClockModel;
        mSatelliteOrbitModel = builder.mSatelliteOrbitModel;
    }

    /**
     * Returns the L1/Satellite system (R), satellite number (slot number in sat. constellation).
     */
    @IntRange(from = 1, to = 25)
    public int getSlotNumber() {
        return mSlotNumber;
    }

    /** Returns the health state. */
    public @GlonassHealthStatus int getHealthState() {
        return mHealthState;
    }

    /** Returns the message frame time in seconds of the UTC week (tk+nd*86400). */
    @FloatRange(from = 0.0f)
    public double getFrameTimeSeconds() {
        return mFrameTimeSeconds;
    }

    /** Returns the age of current information in days (E). */
    @IntRange(from = 0, to = 31)
    public int getAgeInDays() {
        return mAgeInDays;
    }

    /** Returns the update interval in minutes (P1). */
    @IntRange(from = 0, to = 60)
    public int getUpdateIntervalMinutes() {
        return mUpdateIntervalMinutes;
    }

    /** Returns true if the update interval (P2) is odd, false otherwise (P2). */
    public boolean isUpdateIntervalOdd() {
        return mUpdateIntervalOdd;
    }

    /** Returns true if the satellite is a Glonass-M satellitee (M), false otherwise. */
    public boolean isGlonassM() {
        return mGlonassM;
    }

    /** Returns the satellite clock model. */
    @NonNull
    public GlonassSatelliteClockModel getSatelliteClockModel() {
        return mSatelliteClockModel;
    }

    /** Returns the satellite orbit model. */
    @NonNull
    public GlonassSatelliteOrbitModel getSatelliteOrbitModel() {
        return mSatelliteOrbitModel;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSlotNumber);
        dest.writeInt(mHealthState);
        dest.writeDouble(mFrameTimeSeconds);
        dest.writeInt(mAgeInDays);
        dest.writeInt(mUpdateIntervalMinutes);
        dest.writeBoolean(mUpdateIntervalOdd);
        dest.writeBoolean(mGlonassM);
        dest.writeTypedObject(mSatelliteClockModel, flags);
        dest.writeTypedObject(mSatelliteOrbitModel, flags);
    }

    public static final @NonNull Parcelable.Creator<GlonassSatelliteEphemeris> CREATOR =
            new Parcelable.Creator<GlonassSatelliteEphemeris>() {
                @Override
                public GlonassSatelliteEphemeris createFromParcel(@NonNull Parcel source) {
                    return new GlonassSatelliteEphemeris.Builder()
                            .setSlotNumber(source.readInt())
                            .setHealthState(source.readInt())
                            .setFrameTimeSeconds(source.readDouble())
                            .setAgeInDays(source.readInt())
                            .setUpdateIntervalMinutes(source.readInt())
                            .setUpdateIntervalOdd(source.readBoolean())
                            .setGlonassM(source.readBoolean())
                            .setSatelliteClockModel(
                                    source.readTypedObject(GlonassSatelliteClockModel.CREATOR))
                            .setSatelliteOrbitModel(
                                    source.readTypedObject(GlonassSatelliteOrbitModel.CREATOR))
                            .build();
                }

                @Override
                public GlonassSatelliteEphemeris[] newArray(int size) {
                    return new GlonassSatelliteEphemeris[size];
                }
            };

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GlonassSatelliteEphemeris[");
        builder.append("slotNumber = ").append(mSlotNumber);
        builder.append(", healthState = ").append(mHealthState);
        builder.append(", frameTimeSeconds = ").append(mFrameTimeSeconds);
        builder.append(", ageInDays = ").append(mAgeInDays);
        builder.append(", updateIntervalMinutes = ").append(mUpdateIntervalMinutes);
        builder.append(", isUpdateIntervalOdd = ").append(mUpdateIntervalOdd);
        builder.append(", isGlonassM = ").append(mGlonassM);
        builder.append(", satelliteClockModel = ").append(mSatelliteClockModel);
        builder.append(", satelliteOrbitModel = ").append(mSatelliteOrbitModel);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GlonassSatelliteEphemeris}. */
    public static final class Builder {
        private int mSlotNumber;
        private int mHealthState;
        private double mFrameTimeSeconds;
        private int mAgeInDays;
        private int mUpdateIntervalMinutes;
        private boolean mUpdateIntervalOdd;
        private boolean mGlonassM;
        private GlonassSatelliteClockModel mSatelliteClockModel;
        private GlonassSatelliteOrbitModel mSatelliteOrbitModel;

        /**
         * Sets the L1/Satellite system (R), satellite number (slot number in sat. constellation).
         */
        @NonNull
        public Builder setSlotNumber(@IntRange(from = 1, to = 25) int slotNumber) {
            mSlotNumber = slotNumber;
            return this;
        }

        /** Sets the health state. */
        @NonNull
        public Builder setHealthState(@GlonassHealthStatus int healthState) {
            mHealthState = healthState;
            return this;
        }

        /** Sets the message frame time in seconds of the UTC week (tk+nd*86400). */
        @NonNull
        public Builder setFrameTimeSeconds(@FloatRange(from = 0.0f) double frameTimeSeconds) {
            mFrameTimeSeconds = frameTimeSeconds;
            return this;
        }

        /** Sets the age of current information in days (E). */
        @NonNull
        public Builder setAgeInDays(@IntRange(from = 0, to = 31) int ageInDays) {
            mAgeInDays = ageInDays;
            return this;
        }

        /** Sets the satellite clock model. */
        @NonNull
        public Builder setSatelliteClockModel(
                @NonNull GlonassSatelliteClockModel satelliteClockModel) {
            mSatelliteClockModel = satelliteClockModel;
            return this;
        }

        /** Sets the satellite orbit model. */
        @NonNull
        public Builder setSatelliteOrbitModel(
                @NonNull GlonassSatelliteOrbitModel satelliteOrbitModel) {
            mSatelliteOrbitModel = satelliteOrbitModel;
            return this;
        }

        /** Sets the update interval in minutes (P1). */
        @NonNull
        public Builder setUpdateIntervalMinutes(
                @IntRange(from = 0, to = 60) int updateIntervalMinutes) {
            mUpdateIntervalMinutes = updateIntervalMinutes;
            return this;
        }

        /** Sets to true if the update interval (P2) is odd, false otherwise. */
        @NonNull
        public Builder setUpdateIntervalOdd(boolean isUpdateIntervalOdd) {
            mUpdateIntervalOdd = isUpdateIntervalOdd;
            return this;
        }

        /** Sets to true if the satellite is a Glonass-M satellitee (M), false otherwise. */
        @NonNull
        public Builder setGlonassM(boolean isGlonassM) {
            mGlonassM = isGlonassM;
            return this;
        }

        /** Builds a {@link GlonassSatelliteEphemeris}. */
        @NonNull
        public GlonassSatelliteEphemeris build() {
            return new GlonassSatelliteEphemeris(this);
        }
    }

    /**
     * A class contains the set of parameters needed for Glonass satellite clock correction.
     *
     * <p>This is defined in RINEX 3.05 APPENDIX 10 and Glonass ICD v5.1 section 4.4.
     */
    public static final class GlonassSatelliteClockModel implements Parcelable {
        /**
         * Time of the clock in seconds (UTC)
         *
         * <p>Corresponds to the 'Epoch' field within the 'SV/EPOCH/SV CLK' record of Glonass
         * navigation message in RINEX 3.05 Table A10.
         */
        private final long mTimeOfClockSeconds;

        /** Clock bias in seconds (-TauN). */
        private final double mClockBias;

        /** Frequency bias (+GammaN). */
        private final double mFrequencyBias;

        /** Frequency channel number. */
        private final int mFrequencyChannelNumber;

        /* L1/L2 group delay difference in seconds (DeltaTau). */
        private final double mGroupDelayDiffSeconds;

        /**
         * Whether the L1/L2 group delay difference in seconds (DeltaTau) is available.
         *
         * <p>It is set to true if available, otherwise false.
         */
        private final boolean mGroupDelayDiffSecondsAvailable;

        private GlonassSatelliteClockModel(Builder builder) {
            Preconditions.checkArgument(builder.mTimeOfClockSeconds >= 0);
            Preconditions.checkArgumentInRange(builder.mClockBias, -0.002f, 0.002f, "ClockBias");
            Preconditions.checkArgumentInRange(
                    builder.mFrequencyBias, -9.32e-10f, 9.32e-10f, "FrequencyBias");
            Preconditions.checkArgumentInRange(
                    builder.mFrequencyChannelNumber, -7, 6, "FrequencyChannelNumber");
            if (builder.mGroupDelayDiffSecondsAvailable) {
                Preconditions.checkArgumentInRange(
                        builder.mGroupDelayDiffSeconds, -1.4e-8f, 1.4e-8f, "GroupDelayDiffSeconds");
            }
            mTimeOfClockSeconds = builder.mTimeOfClockSeconds;
            mClockBias = builder.mClockBias;
            mFrequencyBias = builder.mFrequencyBias;
            mFrequencyChannelNumber = builder.mFrequencyChannelNumber;
            mGroupDelayDiffSeconds = builder.mGroupDelayDiffSeconds;
            mGroupDelayDiffSecondsAvailable = builder.mGroupDelayDiffSecondsAvailable;
        }

        /** Returns the time of clock in seconds (UTC). */
        @IntRange(from = 0)
        public long getTimeOfClockSeconds() {
            return mTimeOfClockSeconds;
        }

        /** Returns the clock bias in seconds (-TauN). */
        @FloatRange(from = -0.002f, to = 0.002f)
        public double getClockBias() {
            return mClockBias;
        }

        /** Returns the frequency bias (+GammaN). */
        @FloatRange(from = -9.32e-10f, to = 9.32e-10f)
        public double getFrequencyBias() {
            return mFrequencyBias;
        }

        /** Returns the Frequency channel number. */
        @IntRange(from = -7, to = 6)
        public int getFrequencyChannelNumber() {
            return mFrequencyChannelNumber;
        }

        /** Returns the L1/L2 group delay difference in seconds (DeltaTau). */
        @FloatRange(from = -1.4e-8f, to = 1.4e-8f)
        public double getGroupDelayDiffSeconds() {
            return mGroupDelayDiffSeconds;
        }

        /** Returns whether the L1/L2 group delay difference in seconds (DeltaTau) is available. */
        public boolean isGroupDelayDiffSecondsAvailable() {
            return mGroupDelayDiffSecondsAvailable;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeLong(mTimeOfClockSeconds);
            dest.writeDouble(mClockBias);
            dest.writeDouble(mFrequencyBias);
            dest.writeInt(mFrequencyChannelNumber);
            dest.writeDouble(mGroupDelayDiffSeconds);
            dest.writeBoolean(mGroupDelayDiffSecondsAvailable);
        }

        public static final @NonNull Parcelable.Creator<GlonassSatelliteClockModel> CREATOR =
                new Parcelable.Creator<GlonassSatelliteClockModel>() {
                    @Override
                    public GlonassSatelliteClockModel createFromParcel(@NonNull Parcel source) {
                        return new GlonassSatelliteClockModel.Builder()
                                .setTimeOfClockSeconds(source.readLong())
                                .setClockBias(source.readDouble())
                                .setFrequencyBias(source.readDouble())
                                .setFrequencyChannelNumber(source.readInt())
                                .setGroupDelayDiffSeconds(source.readDouble())
                                .setGroupDelayDiffSecondsAvailable(source.readBoolean())
                                .build();
                    }

                    @Override
                    public GlonassSatelliteClockModel[] newArray(int size) {
                        return new GlonassSatelliteClockModel[size];
                    }
                };

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GlonassSatelliteClockModel[");
            builder.append("timeOfClockSeconds = ").append(mTimeOfClockSeconds);
            builder.append(", clockBias = ").append(mClockBias);
            builder.append(", frequencyBias = ").append(mFrequencyBias);
            builder.append(", frequencyChannelNumber = ").append(mFrequencyChannelNumber);
            if (mGroupDelayDiffSecondsAvailable) {
                builder.append(", groupDelayDiffSeconds = ").append(mGroupDelayDiffSeconds);
            }
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GlonassSatelliteClockModel}. */
        public static final class Builder {
            private long mTimeOfClockSeconds;
            private double mClockBias;
            private double mFrequencyBias;
            private double mGroupDelayDiffSeconds;
            private int mFrequencyChannelNumber;
            private boolean mGroupDelayDiffSecondsAvailable;

            /** Sets the time of clock in seconds (UTC). */
            @NonNull
            public Builder setTimeOfClockSeconds(@IntRange(from = 0) long timeOfClockSeconds) {
                mTimeOfClockSeconds = timeOfClockSeconds;
                return this;
            }

            /** Sets the clock bias in seconds (-TauN). */
            @NonNull
            public Builder setClockBias(@FloatRange(from = -0.002f, to = 0.002f) double clockBias) {
                mClockBias = clockBias;
                return this;
            }

            /** Sets the frequency bias (+GammaN). */
            @NonNull
            public Builder setFrequencyBias(
                    @FloatRange(from = -9.32e-10f, to = 9.32e-10f) double frequencyBias) {
                mFrequencyBias = frequencyBias;
                return this;
            }

            /** Sets the Frequency channel number. */
            @NonNull
            public Builder setFrequencyChannelNumber(
                    @IntRange(from = -7, to = 6) int frequencyChannelNumber) {
                mFrequencyChannelNumber = frequencyChannelNumber;
                return this;
            }

            /** Sets the L1/L2 group delay difference in seconds (DeltaTau). */
            @NonNull
            public Builder setGroupDelayDiffSeconds(
                    @FloatRange(from = -1.4e-8f, to = 1.4e-8f) double groupDelayDiffSeconds) {
                mGroupDelayDiffSeconds = groupDelayDiffSeconds;
                return this;
            }

            /** Sets whether the L1/L2 group delay difference in seconds (DeltaTau) is available. */
            @NonNull
            public Builder setGroupDelayDiffSecondsAvailable(
                    boolean isGroupDelayDiffSecondsAvailable) {
                mGroupDelayDiffSecondsAvailable = isGroupDelayDiffSecondsAvailable;
                return this;
            }

            /** Builds a {@link GlonassSatelliteClockModel}. */
            @NonNull
            public GlonassSatelliteClockModel build() {
                return new GlonassSatelliteClockModel(this);
            }
        }
    }

    /**
     * A class contains the set of parameters needed for Glonass satellite orbit correction.
     *
     * <p>This is defined in RINEX 3.05 APPENDIX 10 and Glonass ICD v5.1 section 4.4.
     */
    public static final class GlonassSatelliteOrbitModel implements Parcelable {
        /** X position in kilometers. */
        private final double mX;

        /** X velocity in kilometers per second. */
        private final double mXDot;

        /** X acceleration in kilometers per second squared. */
        private final double mXAccel;

        /** Y position in kilometers. */
        private final double mY;

        /** Y velocity in kilometers per second. */
        private final double mYDot;

        /** Y acceleration in kilometers per second squared. */
        private final double mYAccel;

        /** Z position in kilometers. */
        private final double mZ;

        /** Z velocity in kilometers per second. */
        private final double mZDot;

        /** Z acceleration in kilometers per second squared. */
        private final double mZAccel;

        private GlonassSatelliteOrbitModel(Builder builder) {
            Preconditions.checkArgumentInRange(builder.mX, -2.7e4f, 2.7e4f, "X");
            Preconditions.checkArgumentInRange(builder.mXDot, -4.3f, 4.3f, "XDot");
            Preconditions.checkArgumentInRange(builder.mXAccel, -6.2e-9f, 6.2e-9f, "XAccel");
            Preconditions.checkArgumentInRange(builder.mY, -2.7e4f, 2.7e4f, "Y");
            Preconditions.checkArgumentInRange(builder.mYDot, -4.3f, 4.3f, "YDot");
            Preconditions.checkArgumentInRange(builder.mYAccel, -6.2e-9f, 6.2e-9f, "YAccel");
            Preconditions.checkArgumentInRange(builder.mZ, -2.7e4f, 2.7e4f, "Z");
            Preconditions.checkArgumentInRange(builder.mZDot, -4.3f, 4.3f, "ZDot");
            Preconditions.checkArgumentInRange(builder.mZAccel, -6.2e-9f, 6.2e-9f, "ZAccel");
            mX = builder.mX;
            mXDot = builder.mXDot;
            mXAccel = builder.mXAccel;
            mY = builder.mY;
            mYDot = builder.mYDot;
            mYAccel = builder.mYAccel;
            mZ = builder.mZ;
            mZDot = builder.mZDot;
            mZAccel = builder.mZAccel;
        }

        /** Returns the X position in kilometers. */
        @FloatRange(from = -2.7e4f, to = 2.7e4f)
        public double getX() {
            return mX;
        }

        /** Returns the X velocity in kilometers per second. */
        @FloatRange(from = -4.3f, to = 4.3f)
        public double getXDot() {
            return mXDot;
        }

        /** Returns the X acceleration in kilometers per second squared. */
        @FloatRange(from = -6.2e-9f, to = 6.2e-9f)
        public double getXAccel() {
            return mXAccel;
        }

        /** Returns the Y position in kilometers. */
        @FloatRange(from = -2.7e4f, to = 2.7e4f)
        public double getY() {
            return mY;
        }

        /** Returns the Y velocity in kilometers per second. */
        @FloatRange(from = -4.3f, to = 4.3f)
        public double getYDot() {
            return mYDot;
        }

        /** Returns the Y acceleration in kilometers per second squared. */
        @FloatRange(from = -6.2e-9f, to = 6.2e-9f)
        public double getYAccel() {
            return mYAccel;
        }

        /** Returns the Z position in kilometers. */
        @FloatRange(from = -2.7e4f, to = 2.7e4f)
        public double getZ() {
            return mZ;
        }

        /** Returns the Z velocity in kilometers per second. */
        @FloatRange(from = -4.3f, to = 4.3f)
        public double getZDot() {
            return mZDot;
        }

        /** Returns the Z acceleration in kilometers per second squared. */
        @FloatRange(from = -6.2e-9f, to = 6.2e-9f)
        public double getZAccel() {
            return mZAccel;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mX);
            dest.writeDouble(mXDot);
            dest.writeDouble(mXAccel);
            dest.writeDouble(mY);
            dest.writeDouble(mYDot);
            dest.writeDouble(mYAccel);
            dest.writeDouble(mZ);
            dest.writeDouble(mZDot);
            dest.writeDouble(mZAccel);
        }

        public static final @NonNull Parcelable.Creator<GlonassSatelliteOrbitModel> CREATOR =
                new Parcelable.Creator<GlonassSatelliteOrbitModel>() {
                    @Override
                    public GlonassSatelliteOrbitModel createFromParcel(@NonNull Parcel source) {
                        return new GlonassSatelliteOrbitModel.Builder()
                                .setX(source.readDouble())
                                .setXDot(source.readDouble())
                                .setXAccel(source.readDouble())
                                .setY(source.readDouble())
                                .setYDot(source.readDouble())
                                .setYAccel(source.readDouble())
                                .setZ(source.readDouble())
                                .setZDot(source.readDouble())
                                .setZAccel(source.readDouble())
                                .build();
                    }

                    @Override
                    public GlonassSatelliteOrbitModel[] newArray(int size) {
                        return new GlonassSatelliteOrbitModel[size];
                    }
                };

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GlonassSatelliteOrbitModel[");
            builder.append("x = ").append(mX);
            builder.append(", xDot = ").append(mXDot);
            builder.append(", xAccel = ").append(mXAccel);
            builder.append(", y = ").append(mY);
            builder.append(", yDot = ").append(mYDot);
            builder.append(", yAccel = ").append(mYAccel);
            builder.append(", z = ").append(mZ);
            builder.append(", zDot = ").append(mZDot);
            builder.append(", zAccel = ").append(mZAccel);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GlonassSatelliteOrbitModel}. */
        public static final class Builder {
            private double mX;
            private double mXDot;
            private double mXAccel;
            private double mY;
            private double mYDot;
            private double mYAccel;
            private double mZ;
            private double mZDot;
            private double mZAccel;

            /** Sets the X position in kilometers. */
            @NonNull
            public Builder setX(@FloatRange(from = -2.7e4f, to = 2.7e4f) double x) {
                mX = x;
                return this;
            }

            /** Sets the X velocity in kilometers per second. */
            @NonNull
            public Builder setXDot(@FloatRange(from = -4.3f, to = 4.3f) double xDot) {
                mXDot = xDot;
                return this;
            }

            /** Sets the X acceleration in kilometers per second squared. */
            @NonNull
            public Builder setXAccel(@FloatRange(from = -6.2e-9f, to = 6.2e-9f) double xAccel) {
                mXAccel = xAccel;
                return this;
            }

            /** Sets the Y position in kilometers. */
            @NonNull
            public Builder setY(@FloatRange(from = -2.7e4f, to = 2.7e4f) double y) {
                mY = y;
                return this;
            }

            /** Sets the Y velocity in kilometers per second. */
            @NonNull
            public Builder setYDot(@FloatRange(from = -4.3f, to = 4.3f) double yDot) {
                mYDot = yDot;
                return this;
            }

            /** Sets the Y acceleration in kilometers per second squared. */
            @NonNull
            public Builder setYAccel(@FloatRange(from = -6.2e-9f, to = 6.2e-9f) double yAccel) {
                mYAccel = yAccel;
                return this;
            }

            /** Sets the Z position in kilometers. */
            @NonNull
            public Builder setZ(@FloatRange(from = -2.7e4f, to = 2.7e4f) double z) {
                mZ = z;
                return this;
            }

            /** Sets the Z velocity in kilometers per second. */
            @NonNull
            public Builder setZDot(@FloatRange(from = -4.3f, to = 4.3f) double zDot) {
                mZDot = zDot;
                return this;
            }

            /** Sets the Z acceleration in kilometers per second squared. */
            @NonNull
            public Builder setZAccel(@FloatRange(from = -6.2e-9f, to = 6.2e-9f) double zAccel) {
                mZAccel = zAccel;
                return this;
            }

            /** Builds a {@link GlonassSatelliteOrbitModel}. */
            @NonNull
            public GlonassSatelliteOrbitModel build() {
                return new GlonassSatelliteOrbitModel(this);
            }
        }
    }
}
