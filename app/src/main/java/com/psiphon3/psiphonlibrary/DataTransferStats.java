/*
 * Copyright (c) 2016, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import java.util.ArrayList;

public class DataTransferStats {
    // Singleton pattern

    private static DataTransferStatsForService m_dataTransferStatsForService;
    private static DataTransferStatsForUI m_dataTransferStatsForUI;

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public static synchronized DataTransferStatsForService getDataTransferStatsForService() {
        if (m_dataTransferStatsForService == null) {
            m_dataTransferStatsForService = new DataTransferStatsForService();
        }
        return m_dataTransferStatsForService;
    }

    public static synchronized DataTransferStatsForUI getDataTransferStatsForUI() {
        if (m_dataTransferStatsForUI == null) {
            m_dataTransferStatsForUI = new DataTransferStatsForUI();
        }
        return m_dataTransferStatsForUI;
    }

    public static abstract class DataTransferStatsBase {
        private static final long SLOW_BUCKET_PERIOD_MILLISECONDS = 5 * 60 * 1000;
        private static final long FAST_BUCKET_PERIOD_MILLISECONDS = 1000;
        private static final int MAX_BUCKETS = 24 * 60 / 5;

        protected static class Bucket implements Parcelable {
            public long m_bytesSent = 0;
            public long m_bytesReceived = 0;

            protected Bucket() {

            }

            protected Bucket(Parcel in) {
                m_bytesSent = in.readLong();
                m_bytesReceived = in.readLong();
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
                dest.writeLong(m_bytesSent);
                dest.writeLong(m_bytesReceived);
            }

            public static final Creator<Bucket> CREATOR = new Creator<Bucket>() {
                @Override
                public Bucket createFromParcel(Parcel in) {
                    return new Bucket(in);
                }

                @Override
                public Bucket[] newArray(int size) {
                    return new Bucket[size];
                }
            };
        }

        protected long m_connectedTime;
        protected long m_totalBytesSent;
        protected long m_totalBytesReceived;
        protected ArrayList<Bucket> m_slowBuckets;
        protected long m_slowBucketsLastStartTime;
        protected ArrayList<Bucket> m_fastBuckets;
        protected long m_fastBucketsLastStartTime;

        private DataTransferStatsBase() {
            m_totalBytesSent = 0;
            m_totalBytesReceived = 0;

            stop();
        }

        public synchronized void stop() {
            m_connectedTime = 0;
            resetBytesTransferred();
        }

        protected void resetBytesTransferred() {
            long now = SystemClock.elapsedRealtime();
            m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            m_slowBuckets = newBuckets();
            m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            m_fastBuckets = newBuckets();
        }

        private long bucketStartTime(long now, long period) {
            return period * (now / period);
        }

        private ArrayList<Bucket> newBuckets() {
            // Pre-sized: the list is always exactly MAX_BUCKETS long, so there is no
            // reason to let ArrayList grow (and copy) its way there.
            ArrayList<Bucket> buckets = new ArrayList<>(MAX_BUCKETS);
            for (int i = 0; i < MAX_BUCKETS; i++) {
                buckets.add(new Bucket());
            }
            return buckets;
        }

        private void refillWithEmptyBuckets(ArrayList<Bucket> buckets) {
            buckets.clear();
            for (int i = 0; i < MAX_BUCKETS; i++) {
                buckets.add(new Bucket());
            }
        }

        /**
         * Advances the bucket window by the number of periods that have elapsed.
         *
         * This is on the tunnel data path (manageBuckets() is called from every
         * addBytesSent/addBytesReceived), so cost matters. The previous implementation
         * looped once per elapsed period and called ArrayList.remove(0) each time,
         * which is an O(MAX_BUCKETS) arraycopy. For the 1 second fast buckets a long
         * idle gap meant an unbounded loop: a 24 hour gap is ~86,400 iterations x a
         * 288 element shift, all while holding the instance monitor.
         *
         * Two changes, both output-identical:
         *   1. Once MAX_BUCKETS periods have elapsed every pre-existing bucket has
         *      been evicted, so the result is just a window of empty buckets. Clamp
         *      there instead of spinning.
         *   2. Evict the oldest buckets in a single batch removal rather than one
         *      remove(0) per shift.
         */
        private void shiftBuckets(long diff, long period, ArrayList<Bucket> buckets) {
            final long shifts = diff / period + 1;

            if (shifts >= MAX_BUCKETS) {
                refillWithEmptyBuckets(buckets);
                return;
            }

            final int shiftCount = (int) shifts;
            final int startSize = buckets.size();

            // Mirror the original per-shift eviction rule ("after appending, drop the
            // oldest bucket if the window is full") without touching the array each
            // time; this only differs from a plain min() when the list somehow starts
            // out shorter than MAX_BUCKETS, e.g. after a partial parcel restore.
            int size = startSize;
            int evictions = 0;
            for (int i = 0; i < shiftCount; i++) {
                size++;
                if (size >= MAX_BUCKETS) {
                    size--;
                    evictions++;
                }
            }
            if (evictions > startSize) {
                evictions = startSize;
            }

            buckets.ensureCapacity(startSize + shiftCount);
            for (int i = 0; i < shiftCount; i++) {
                buckets.add(new Bucket());
            }
            if (evictions > 0) {
                // One arraycopy instead of `evictions` of them.
                buckets.subList(0, evictions).clear();
            }
        }

        protected void manageBuckets() {
            long now = SystemClock.elapsedRealtime();

            long diff = now - m_slowBucketsLastStartTime;
            if (diff >= SLOW_BUCKET_PERIOD_MILLISECONDS) {
                shiftBuckets(diff, SLOW_BUCKET_PERIOD_MILLISECONDS, m_slowBuckets);
                m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            }

            diff = now - m_fastBucketsLastStartTime;
            if (diff >= FAST_BUCKET_PERIOD_MILLISECONDS) {
                shiftBuckets(diff, FAST_BUCKET_PERIOD_MILLISECONDS, m_fastBuckets);
                m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            }
        }
    }

    public static class DataTransferStatsForService extends DataTransferStatsBase {
        private DataTransferStatsForService() {

        }

        public synchronized void startSession() {
            resetBytesTransferred();
        }

        public synchronized void startConnected() {
            m_connectedTime = SystemClock.elapsedRealtime();
        }

        public synchronized void addBytesSent(long bytes) {
            m_totalBytesSent += bytes;

            manageBuckets();
            addSentToBuckets(bytes);
        }

        public synchronized void addBytesReceived(long bytes) {
            m_totalBytesReceived += bytes;

            manageBuckets();
            addReceivedToBuckets(bytes);
        }

        private void addSentToBuckets(long bytes) {
            m_slowBuckets.get(m_slowBuckets.size() - 1).m_bytesSent += bytes;
            m_fastBuckets.get(m_fastBuckets.size() - 1).m_bytesSent += bytes;
        }

        private void addReceivedToBuckets(long bytes) {
            m_slowBuckets.get(m_slowBuckets.size() - 1).m_bytesReceived += bytes;
            m_fastBuckets.get(m_fastBuckets.size() - 1).m_bytesReceived += bytes;
        }
    }

    public static class DataTransferStatsForUI extends DataTransferStatsBase {
        private DataTransferStatsForUI() {

        }

        private ArrayList<Long> getSentSeries(ArrayList<Bucket> buckets) {
            final int size = buckets.size();
            ArrayList<Long> series = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                series.add(buckets.get(i).m_bytesSent);
            }
            return series;
        }

        private ArrayList<Long> getReceivedSeries(ArrayList<Bucket> buckets) {
            final int size = buckets.size();
            ArrayList<Long> series = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                series.add(buckets.get(i).m_bytesReceived);
            }
            return series;
        }

        public synchronized long getElapsedTime() {
            long now = SystemClock.elapsedRealtime();

            return now - this.m_connectedTime;
        }

        public synchronized long getTotalBytesSent() {
            return this.m_totalBytesSent;
        }

        public synchronized long getTotalBytesReceived() {
            return this.m_totalBytesReceived;
        }

        public synchronized ArrayList<Long> getSlowSentSeries() {
            manageBuckets();
            return getSentSeries(this.m_slowBuckets);
        }

        public synchronized ArrayList<Long> getSlowReceivedSeries() {
            manageBuckets();
            return getReceivedSeries(this.m_slowBuckets);
        }

        public synchronized ArrayList<Long> getFastSentSeries() {
            manageBuckets();
            return getSentSeries(this.m_fastBuckets);
        }

        public synchronized ArrayList<Long> getFastReceivedSeries() {
            manageBuckets();
            return getReceivedSeries(this.m_fastBuckets);
        }
    }
}
