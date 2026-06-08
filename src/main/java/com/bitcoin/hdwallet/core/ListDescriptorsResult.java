package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */


import java.util.List;

public class ListDescriptorsResult {

    private final List<DescriptorInfo> descriptors;

    public ListDescriptorsResult(
            List<DescriptorInfo> descriptors
    ) {
        this.descriptors = descriptors;
    }

    public List<DescriptorInfo> getDescriptors() {
        return descriptors;
    }

    // --------------------------------------------------------
    // DescriptorInfo
    // --------------------------------------------------------

    public static class DescriptorInfo {

        private final String desc;
        private final long timestamp;
        private final boolean active;
        private final boolean internal;
        private final int[] range;
        private final boolean nextIndexPresent;
        private final int nextIndex;

        public DescriptorInfo(
                String desc,
                long timestamp,
                boolean active,
                boolean internal,
                int[] range,
                boolean nextIndexPresent,
                int nextIndex
        ) {

            this.desc = desc;
            this.timestamp = timestamp;
            this.active = active;
            this.internal = internal;
            this.range = range;
            this.nextIndexPresent = nextIndexPresent;
            this.nextIndex = nextIndex;
        }

        public String getDesc() {
            return desc;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isActive() {
            return active;
        }

        public boolean isInternal() {
            return internal;
        }

        public int[] getRange() {
            return range;
        }

        public boolean hasNextIndex() {
            return nextIndexPresent;
        }

        public int getNextIndex() {
            return nextIndex;
        }

        @Override
        public String toString() {

            return "DescriptorInfo{" +
                    "desc='" + desc + '\'' +
                    ", active=" + active +
                    ", internal=" + internal +
                    '}';
        }
    }
}