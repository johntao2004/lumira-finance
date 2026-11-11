package com.lumira.finance.payrolltax;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * TAX05: Form-version cell lock (锁版) engine.
 * Once a tax declaration or worksheet version is locked, cells are strictly immutable.
 * Any attempt to mutate cell values in a locked form is blocked.
 */
public final class FormVersionCellLock {

    private FormVersionCellLock() {}

    public record FormLockState(
            UUID id,
            UUID taxObligationId,
            String formCode,
            int versionNumber,
            boolean isLocked,
            Instant lockedAt,
            UUID lockedByNaturalPerson,
            String contentDigest,
            Map<String, String> cellValues
    ) implements Serializable {
        public FormLockState {
            Objects.requireNonNull(id, "ID_REQUIRED");
            Objects.requireNonNull(taxObligationId, "TAX_OBLIGATION_ID_REQUIRED");
            Objects.requireNonNull(formCode, "FORM_CODE_REQUIRED");
            Objects.requireNonNull(contentDigest, "CONTENT_DIGEST_REQUIRED");
            cellValues = cellValues == null ? Map.of() : Map.copyOf(cellValues);
        }

        public void assertEditable() {
            if (isLocked) {
                throw new IllegalStateException("FORM_VERSION_LOCKED: Form " + formCode + " version " + versionNumber + " is locked and immutable");
            }
        }

        public FormLockState lock(UUID naturalPersonId, String newDigest) {
            assertEditable();
            if (naturalPersonId == null) throw new IllegalArgumentException("LOCKING_PERSON_REQUIRED");
            if (newDigest == null || !newDigest.matches("^[a-f0-9]{64}$"))
                throw new IllegalArgumentException("INVALID_LOCK_DIGEST");
            return new FormLockState(
                    id,
                    taxObligationId,
                    formCode,
                    versionNumber,
                    true,
                    Instant.now(),
                    naturalPersonId,
                    newDigest,
                    cellValues
            );
        }
    }
}
