package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TridentChargePolicyTest {
    @Test void vanillaDefaultAndConfiguredReductionAreReal() {
        assertEquals(10, TridentChargePolicy.minimum(10, 1.0));
        assertEquals(5, TridentChargePolicy.minimum(10, 0.5));
        assertEquals(1, TridentChargePolicy.minimum(10, 0.0));
    }

    @Test void malformedValuesCannotRemoveTheMinimumOrSpeedUpAccidentally() {
        assertEquals(10, TridentChargePolicy.minimum(10, Double.NaN));
        assertEquals(10, TridentChargePolicy.minimum(10, Double.POSITIVE_INFINITY));
        assertEquals(10, TridentChargePolicy.minimum(10, 10));
        assertEquals(1, TridentChargePolicy.minimum(10, -1));
        assertEquals(0, TridentChargePolicy.minimum(0, 0.5));
    }

    @Test void manualRepeatFloorDoesNotOverrideLongerItemCharge() {
        assertEquals(9, TridentChargePolicy.repeatMinimum(1));
        assertEquals(10, TridentChargePolicy.repeatMinimum(10));
        assertEquals(20, TridentChargePolicy.repeatMinimum(20));
    }
}
