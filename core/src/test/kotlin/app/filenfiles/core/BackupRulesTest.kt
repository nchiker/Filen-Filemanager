package app.filenfiles.core

import java.util.TimeZone
import kotlin.test.*

class BackupRulesTest {
    @Test fun picksPhotosAndOptionallyVideos() {
        assertTrue(BackupRules.isBackupCandidate("IMG_1.JPG", false))
        assertTrue(BackupRules.isBackupCandidate("a.heic", false))
        assertFalse(BackupRules.isBackupCandidate("clip.mp4", false))
        assertTrue(BackupRules.isBackupCandidate("clip.MP4", true))
        assertFalse(BackupRules.isBackupCandidate("notes.txt", true))
        assertFalse(BackupRules.isBackupCandidate("noextension", true))
    }

    @Test fun skipsHiddenPendingAndTrashed() {
        assertFalse(BackupRules.isBackupCandidate(".pending-1234-IMG_1.jpg", true))
        assertFalse(BackupRules.isBackupCandidate(".trashed-99-IMG_2.jpg", true))
        assertFalse(BackupRules.isBackupCandidate(".thumbnails", true))
    }

    @Test fun monthFolderUsesTheGivenZone() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("2026-11", BackupRules.monthFolder(1_793_966_400_000L, utc)) // 2026-11-06T12:00Z
        assertEquals("2024-01", BackupRules.monthFolder(1_704_067_200_000L, utc)) // 2024-01-01T00:00Z
        assertEquals("2023-12", BackupRules.monthFolder(1_704_067_200_000L, TimeZone.getTimeZone("America/New_York")))
    }

    @Test fun keyChangesWithContentMarkers() {
        val k = BackupRules.key("/a/b.jpg", 10, 5)
        assertEquals(k, BackupRules.key("/a/b.jpg", 10, 5))
        assertNotEquals(k, BackupRules.key("/a/b.jpg", 11, 5))
        assertNotEquals(k, BackupRules.key("/a/b.jpg", 10, 6))
    }

    @Test fun settlesAfterAMoment() {
        assertFalse(BackupRules.isSettled(modified = 1_000, now = 10_000))
        assertTrue(BackupRules.isSettled(modified = 1_000, now = 40_000))
    }
}
