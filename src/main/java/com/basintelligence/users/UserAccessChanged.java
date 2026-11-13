package com.basintelligence.users;

/**
 * Published after an administrator changes a user's password, roles, factory or enabled flag. Listeners end the
 * user's web sessions and forget cached API credentials, so the change takes effect at once.
 */
public record UserAccessChanged(String username) {
}
