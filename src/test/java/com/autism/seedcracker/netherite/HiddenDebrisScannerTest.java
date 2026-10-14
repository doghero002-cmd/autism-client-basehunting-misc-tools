package com.autism.seedcracker.netherite;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HiddenDebrisScannerTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static FriendlyByteBuf section(boolean used) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
        data.writeShort(4096); data.writeShort(0);
        data.writeByte(4); data.writeVarInt(2); data.writeVarInt(1); data.writeVarInt(HiddenDebrisScanner.ancientStateId());
        for (int index = 0; index < 256; index++) data.writeLong(used && index == 0 ? 1L : 0L);
        data.writeByte(0); data.writeVarInt(0);
        return data;
    }

    @Test void detectsDeclaredButUnusedTargetInCurrentSectionFormat() {
        FriendlyByteBuf data = section(false);
        try { assertEquals(List.of(-4), HiddenDebrisScanner.findHiddenSections(data, 1, -4, HiddenDebrisScanner.ancientStateId())); }
        finally { data.release(); }
    }

    @Test void usedTargetAndTrailingGarbageAreNotCandidates() {
        FriendlyByteBuf used = section(true), trailing = section(false);
        try {
            trailing.writeByte(99);
            assertTrue(HiddenDebrisScanner.findHiddenSections(used, 1, 0, HiddenDebrisScanner.ancientStateId()).isEmpty());
            assertTrue(HiddenDebrisScanner.findHiddenSections(trailing, 1, 0, HiddenDebrisScanner.ancientStateId()).isEmpty());
        } finally { used.release(); trailing.release(); }
    }

    @Test void truncatedAndInvalidPackedPalettesFailClosed() {
        FriendlyByteBuf truncated = section(false), invalid = section(false);
        try {
            truncated.writerIndex(10);
            invalid.setLong(9, 15L);
            assertTrue(HiddenDebrisScanner.findHiddenSections(truncated, 1, 0, HiddenDebrisScanner.ancientStateId()).isEmpty());
            assertTrue(HiddenDebrisScanner.findHiddenSections(invalid, 1, 0, HiddenDebrisScanner.ancientStateId()).isEmpty());
        } finally { truncated.release(); invalid.release(); }
    }
}