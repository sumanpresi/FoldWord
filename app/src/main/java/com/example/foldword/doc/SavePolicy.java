package com.example.foldword.doc;

import java.util.Set;

/**
 * Decides what pressing Save does. Kept separate from the screen so the "never overwrite by
 * accident" rules can be unit-tested.
 */
public final class SavePolicy {

    private SavePolicy() {}

    public enum Action {
        /** New document: ask where to save it. */
        CHOOSE_LOCATION,
        /** The original contains things the editor cannot keep: only a copy may be saved. */
        SAVE_COPY_ONLY,
        /** The file's location does not allow changes: offer Save As. */
        READ_ONLY,
        /** Ask once before replacing the original file. */
        CONFIRM_REPLACE,
        /** Replace the file (a backup of the previous version is made first). */
        OVERWRITE
    }

    public static Action decide(boolean hasFile, boolean formatCanSave, Set<String> lostFeatures,
                                boolean locationWritable, boolean replaceConfirmed) {
        if (!hasFile || !formatCanSave) return Action.CHOOSE_LOCATION;
        if (lostFeatures != null && !lostFeatures.isEmpty()) return Action.SAVE_COPY_ONLY;
        if (!locationWritable) return Action.READ_ONLY;
        if (!replaceConfirmed) return Action.CONFIRM_REPLACE;
        return Action.OVERWRITE;
    }

    /** Suggested name for a safe copy, for example "Report (edited).docx". */
    public static String copyName(String originalName, Formats format) {
        String base = originalName == null ? "Document" : originalName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return format.fileName(base + " (edited)");
    }
}
