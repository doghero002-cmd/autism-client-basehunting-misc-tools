package com.autism.seedcracker.finder;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScoutJournalTest {
    @TempDir Path directory;
    @Test void oversizedRecordsFailClosedWithoutWriting() throws Exception {
        ScoutJournal journal=new ScoutJournal(directory,UUID.randomUUID());
        try {
            JsonObject event=new JsonObject(); event.addProperty("detail","x".repeat(8192));
            assertFalse(journal.append(event));
        } finally { journal.close(); }
        assertTrue(journal.awaitClosed(5000));
        assertEquals(0,Files.size(journal.file()));
    }

    @Test void closesAndDrainsImmutableQueuedRecords() throws Exception {
        ScoutJournal journal = new ScoutJournal(directory,UUID.randomUUID());
        JsonObject event = new JsonObject(); event.addProperty("type","observation");
        assertTrue(journal.append(event)); event.addProperty("type","mutated-later");
        journal.close(); assertTrue(journal.awaitClosed(5000));
        assertTrue(Files.readString(journal.file()).contains("observation"));
        assertFalse(Files.readString(journal.file()).contains("mutated-later"));
        assertFalse(journal.append(event));
    }

    @Test void manualReviewRequiresAnExistingCandidateAndValidLabel() throws Exception {
        UUID session=UUID.randomUUID(), candidate=UUID.randomUUID();
        ScoutJournal journal=new ScoutJournal(directory,session);
        JsonObject event=new JsonObject(); event.addProperty("type","candidate"); event.addProperty("candidateId",candidate.toString()); event.addProperty("label","unreviewed");
        assertTrue(journal.append(event)); journal.close(); assertTrue(journal.awaitClosed(5000));
        ScoutJournal.review(directory,session.toString(),candidate.toString(),"natural");
        assertTrue(Files.readString(journal.file()).contains("\"reviewer\":\"manual\""));
        assertThrows(IllegalArgumentException.class,()->ScoutJournal.review(directory,session.toString(),UUID.randomUUID().toString(),"confirmed-base"));
        assertThrows(IllegalArgumentException.class,()->ScoutJournal.review(directory,session.toString(),candidate.toString(),"probably"));
        assertThrows(IllegalArgumentException.class,()->ScoutJournal.review(directory,"../escape",candidate.toString(),"natural"));
    }
}