package com.elearning.exception;

/**
 * Thrown at login when an approved account (student or teacher)
 * has been deactivated by the administrator.
 */
public class AccountDisabledException extends RuntimeException {
    public AccountDisabledException() {
        super("Compte désactivé");
    }
}
