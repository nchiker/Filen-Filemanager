package app.filenfiles.filen

import kotlin.test.*

class StackLinkContractTest {
    @Test fun contractLevelIsOneForFolderSharing() = assertEquals(1, StackLink.CONTRACT_LEVEL)
}
