package com.example.foldword.doc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;

/** Overwrite protection, crash-safe writes and draft recovery. */
public class SafetyAndPolicyTest {

    @Test
    public void newDocumentsAskWhereToSave() {
        assertEquals(SavePolicy.Action.CHOOSE_LOCATION, SavePolicy.decide(false, true, null, true, true));
    }

    @Test
    public void documentsWithUnsupportedContentCanOnlyBeSavedAsACopy() {
        assertEquals(SavePolicy.Action.SAVE_COPY_ONLY,
            SavePolicy.decide(true, true, new HashSet<>(Collections.singleton(DocxReader.LOST_IMAGES)), true, true));
    }

    @Test
    public void replacingTheOriginalNeedsConfirmationFirst() {
        assertEquals(SavePolicy.Action.CONFIRM_REPLACE, SavePolicy.decide(true, true, Collections.emptySet(), true, false));
        assertEquals(SavePolicy.Action.OVERWRITE, SavePolicy.decide(true, true, Collections.emptySet(), true, true));
    }

    @Test
    public void readOnlyLocationsOfferSaveAs() {
        assertEquals(SavePolicy.Action.READ_ONLY, SavePolicy.decide(true, true, Collections.emptySet(), false, true));
    }

    @Test
    public void copyNamesNeverMatchTheOriginal() {
        assertEquals("Report (edited).docx", SavePolicy.copyName("Report.docx", Formats.DOCX));
        assertFalse(SavePolicy.copyName("Report.docx", Formats.DOCX).equals("Report.docx"));
    }

    @Test
    public void atomicWriteReplacesContentAndLeavesNoTempFile() throws IOException {
        File dir = Files.createTempDirectory("fw").toFile();
        File f = new File(dir, "draft.json");
        SafeFiles.writeAtomic(f, "one".getBytes(StandardCharsets.UTF_8));
        SafeFiles.writeAtomic(f, "two".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), SafeFiles.read(f));
        String[] left = dir.list();
        assertEquals("Only the final file should remain", 1, left == null ? 0 : left.length);
    }

    @Test
    public void failedWriteKeepsTheOriginalFile() throws IOException {
        File dir = Files.createTempDirectory("fw").toFile();
        File f = new File(dir, "doc.json");
        SafeFiles.writeAtomic(f, "original".getBytes(StandardCharsets.UTF_8));
        // Make the temporary file path a folder, so writing it fails like a full or broken disk would.
        File blocker = new File(dir, ".doc.json.tmp");
        assertTrue(blocker.mkdir());
        try {
            SafeFiles.writeAtomic(f, "new".getBytes(StandardCharsets.UTF_8));
            fail("The write should have failed");
        } catch (IOException expected) { /* ok */ }
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), SafeFiles.read(f));
    }

    @Test
    public void fullStorageIsRecognised() {
        assertTrue(SafeFiles.isNoSpace(new IOException("write failed: ENOSPC (No space left on device)")));
        assertTrue(SafeFiles.isNoSpace(new RuntimeException(new IOException("No space left on device"))));
        assertFalse(SafeFiles.isNoSpace(new IOException("Permission denied")));
    }

    @Test
    public void draftJsonSurvivesARoundTripLikeAfterProcessRecreation() {
        DocModel.Document d = TestDocs.allFeatures();
        String draft = DocJson.toJson(d);
        assertEquals(draft, DocJson.toJson(DocJson.fromJson(draft)));
    }

    @Test
    public void damagedDraftValuesAreClampedNotTrusted() {
        DocModel.Document d = DocJson.fromJson("{\"blocks\":[{\"t\":\"p\",\"style\":\"<script>\",\"level\":500,\"runs\":[{\"text\":\"x\",\"size\":-5}]}]}");
        DocModel.Paragraph p = (DocModel.Paragraph) d.blocks.get(0);
        assertEquals(DocModel.STYLE_NORMAL, p.style);
        assertEquals(8, p.level);
        assertEquals(0, p.runs.get(0).sizePt);
    }
}
