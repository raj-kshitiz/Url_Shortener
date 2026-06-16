package com.example.urlshortener.utilities;

public class Base62Encoding {
    public static String encode(long value) {
        /**
         * Function assumes that the value will definitely generate a code more than 6 in length
         *
         * By using the following logic when called:
         *   long min = (long) Math.pow(62, 5);   // 916,132,832
         *   long max = (long) Math.pow(62, 6);   // 56,800,235,584
         *   long randomValue = ThreadLocalRandom.current().nextLong(min, max);
         *   String shortCode = Base62Encoding.encode(randomValue);
         */
        StringBuilder sb = new StringBuilder();

        while (value > 0) {
            int rem = (int) (value % 62);
            if(rem < 10) {
                sb.append((char) ('0' + rem));
            } else if(rem < 36) {
                sb.append((char) ('A' + rem - 10));
            } else {
                sb.append((char) ('a' + rem - 36));
            }
            value /= 62;
        }
        return sb.reverse().toString();
    }
}
