package com.weeklyroster.util;

import java.util.regex.Pattern;

public final class PhoneUtils {

    private static final Pattern INDIAN_MOBILE_PATTERN = Pattern.compile("^[6-9]\\d{9}$");

    private PhoneUtils() {
    }

    /**
     * Extracts and validates an Indian 10-digit mobile number from various raw input formats:
     * e.g. "9876543210", "+919876543210", "919876543210", "09876543210", "+91 98765-43210".
     *
     * @param rawPhone raw phone string
     * @return 10-digit normalized number if valid, or null if invalid/missing
     */
    public static String normalize10Digits(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return null;
        }

        // Strip non-digit characters except leading plus if any
        String cleaned = rawPhone.trim().replaceAll("[^0-9+]", "");
        if (cleaned.startsWith("+91")) {
            cleaned = cleaned.substring(3);
        } else if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }

        // Remove non-digits completely now
        String digitsOnly = cleaned.replaceAll("[^0-9]", "");

        if (digitsOnly.length() == 12 && digitsOnly.startsWith("91")) {
            digitsOnly = digitsOnly.substring(2);
        } else if (digitsOnly.length() == 11 && digitsOnly.startsWith("0")) {
            digitsOnly = digitsOnly.substring(1);
        }

        if (digitsOnly.length() == 10 && INDIAN_MOBILE_PATTERN.matcher(digitsOnly).matches()) {
            return digitsOnly;
        }

        return null;
    }

    /**
     * Checks whether the given phone string represents a valid Indian mobile number.
     */
    public static boolean isValidIndianMobile(String rawPhone) {
        return normalize10Digits(rawPhone) != null;
    }

    /**
     * Normalizes to E.164 international format (+919876543210).
     */
    public static String normalizeE164(String rawPhone) {
        String ten = normalize10Digits(rawPhone);
        return ten != null ? "+91" + ten : null;
    }

    /**
     * Masks the phone number for safe diagnostic and log output (e.g. "******3210").
     * Never exposes the full phone number.
     */
    public static String maskPhone(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return "******";
        }
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "****";
        }
        if (digits.length() <= 7) {
            return digits.substring(0, 1) + "***" + digits.substring(digits.length() - 2);
        }
        int len = digits.length();
        return "******" + digits.substring(len - 4);
    }
}
