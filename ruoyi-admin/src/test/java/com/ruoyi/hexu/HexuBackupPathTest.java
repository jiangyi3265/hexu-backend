package com.ruoyi.hexu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class HexuBackupPathTest {
    @TempDir Path temp;

    @Test
    void findsScriptBesidePackagedJarWhenStartedElsewhere() throws Exception {
        Path project = temp.resolve("fenxiao-backend");
        Files.createDirectories(project.resolve("scripts"));
        Files.createDirectories(project.resolve(".hexu-local"));
        Files.createFile(project.resolve("scripts/backup-database.mjs"));
        assertEquals(project, HexuBackupController.locateProjectRoot(temp.resolve("unrelated"), project.resolve(".hexu-local")));
    }

    @Test
    void refusesUnrelatedDirectoryWithoutProjectScript() {
        assertThrows(BusinessException.class, () -> HexuBackupController.locateProjectRoot(temp.resolve("work"), temp.resolve("package")));
    }
}
