package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentPolicyTest {
    @Test void recipientsAndAmountsFailClosedOnCommandInjectionAndInvalidValues() {
        assertTrue(PaymentPolicy.recipient(".Bedrock", "Self"));
        assertTrue(PaymentPolicy.recipient("Alt_1", "Self"));
        assertFalse(PaymentPolicy.recipient("SELF", "Self"));
        assertFalse(PaymentPolicy.recipient("Alt pay Other", "Self"));
        assertTrue(PaymentPolicy.amount("all"));
        assertTrue(PaymentPolicy.amount("2.5m"));
        assertFalse(PaymentPolicy.amount("0"));
        assertFalse(PaymentPolicy.amount("-1"));
        assertFalse(PaymentPolicy.amount("NaN"));
        assertFalse(PaymentPolicy.amount("all\npay Other all"));
    }

    @Test void acknowledgementRequiresTheExpectedRecipientAndSuccessPrefix() {
        assertTrue(PaymentPolicy.acknowledged("You paid $1,000 to Alt_1.", "Alt_1"));
        assertTrue(PaymentPolicy.acknowledged("You have sent $1k to .Bedrock", ".Bedrock"));
        assertFalse(PaymentPolicy.acknowledged("Bob: You paid $1k to Alt_1", "Alt_1"));
        assertFalse(PaymentPolicy.acknowledged("You paid $1k to Alt_12.", "Alt_1"));
        assertFalse(PaymentPolicy.acknowledged("You could not pay Alt_1", "Alt_1"));
        assertFalse(PaymentPolicy.acknowledged("You paid $1k to Other", "Alt_1"));
    }
}