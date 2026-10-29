package com.banking.user.email;

import org.springframework.stereotype.Component;

@Component
public class RegistrationMessages {

    public record Message(String subject, String body) {}

    public Message verification(String link, int ttlHours) {
        return new Message(
                "Verify your email address",
                "Hello,\n\n"
                        + "Someone used this email address to register for our banking service.\n"
                        + "If it was you, confirm your email by opening this link (valid for " + ttlHours + " hours):\n\n"
                        + link + "\n\n"
                        + "If you did not register, you can ignore this message. Nothing will happen.");
    }

    public Message alreadyRegistered() {
        return new Message(
                "Registration attempt with your email address",
                "Hello,\n\n"
                        + "Someone tried to create an account with this email address, but an account already exists.\n"
                        + "If it was you, simply log in with your existing password.\n"
                        + "If it was not you, you can ignore this message. No changes were made to your account.");
    }

    public Message accountReady() {
        return new Message(
                "Your account is ready",
                "Hello,\n\nYour account has been created. You can now log in.");
    }
}